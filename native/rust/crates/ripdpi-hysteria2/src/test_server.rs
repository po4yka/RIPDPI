//! In-crate Hysteria 2 loopback server for client tests that need UDP relay,
//! Salamander obfuscation, or a refused TCP request. The shared
//! `local-network-fixture` server answers `Hysteria-UDP: false` and has no
//! Salamander socket, so it cannot drive these paths.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use bytes::{BufMut, BytesMut};
use tokio::sync::mpsc;

use crate::salamander::SalamanderUdpSocket;
use crate::varint::{put_varint, read_varint};

const HYSTERIA_AUTH_STATUS: u16 = 233;
const TCP_REQUEST_COMMAND: u64 = 0x401;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ServerEvent {
    TcpRequest(String),
    Datagram { session_id: u32 },
}

#[derive(Default)]
pub(crate) struct ServerOptions {
    pub(crate) salamander_key: Option<&'static str>,
    pub(crate) refuse_tcp: bool,
}

pub(crate) struct TestServer {
    endpoint: quinn::Endpoint,
    pub(crate) events: mpsc::UnboundedReceiver<ServerEvent>,
}

impl TestServer {
    /// Starts the accept loop. It echoes TCP streams and UDP datagrams; each
    /// echoed datagram keeps its fragment header, so the client reassembles.
    pub(crate) fn start(options: ServerOptions) -> io::Result<Self> {
        let socket = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0))?;
        let server_config = server_config()?;
        let endpoint = match options.salamander_key {
            Some(key) => quinn::Endpoint::new_with_abstract_socket(
                quinn::EndpointConfig::default(),
                Some(server_config),
                Arc::new(SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?),
                Arc::new(quinn::TokioRuntime),
            )?,
            None => quinn::Endpoint::new(
                quinn::EndpointConfig::default(),
                Some(server_config),
                socket,
                Arc::new(quinn::TokioRuntime),
            )?,
        };
        let (events_tx, events) = mpsc::unbounded_channel();
        let accept = endpoint.clone();
        let refuse_tcp = options.refuse_tcp;
        tokio::spawn(async move {
            while let Some(incoming) = accept.accept().await {
                let events_tx = events_tx.clone();
                tokio::spawn(async move {
                    let _ = serve_connection(incoming, events_tx, refuse_tcp).await;
                });
            }
        });
        Ok(Self { endpoint, events })
    }

    pub(crate) fn addr(&self) -> SocketAddr {
        self.endpoint.local_addr().expect("test server address")
    }

    /// A client config for this server that trusts its self-signed cert.
    pub(crate) fn client_config(&self) -> crate::Config {
        let mut config =
            crate::Config::from_parts("auth".into(), "127.0.0.1", i32::from(self.addr().port()), "localhost".into())
                .expect("client config");
        config.insecure = true;
        config
    }

    /// Closes every connection, as a dead server or lost path would.
    pub(crate) fn close(&self) {
        self.endpoint.close(0u32.into(), b"test server closed");
    }
}

fn server_config() -> io::Result<quinn::ServerConfig> {
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).map_err(io::Error::other)?;
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .map_err(io::Error::other)?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der.into())
        .map_err(io::Error::other)?;
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).map_err(io::Error::other)?;
    Ok(quinn::ServerConfig::with_crypto(Arc::new(crypto)))
}

/// # Cancel safety
///
/// cancel-safe: the test owns the connection; dropping the future closes it.
async fn serve_connection(
    incoming: quinn::Incoming,
    events: mpsc::UnboundedSender<ServerEvent>,
    refuse_tcp: bool,
) -> io::Result<()> {
    let connection = incoming.await.map_err(io::Error::other)?;
    // Dropping the h3 connection would close the shared QUIC connection.
    let _h3 = authenticate(connection.clone()).await?;

    let datagrams = connection.clone();
    let datagram_events = events.clone();
    tokio::spawn(async move {
        while let Ok(datagram) = datagrams.read_datagram().await {
            if let Some(session_id) = datagram.get(..4) {
                let session_id = u32::from_be_bytes([session_id[0], session_id[1], session_id[2], session_id[3]]);
                let _ = datagram_events.send(ServerEvent::Datagram { session_id });
            }
            let _ = datagrams.send_datagram(datagram);
        }
    });

    while let Ok((send, recv)) = connection.accept_bi().await {
        let events = events.clone();
        tokio::spawn(async move {
            let _ = serve_tcp_stream(send, recv, events, refuse_tcp).await;
        });
    }
    Ok(())
}

type H3Server = h3::server::Connection<h3_quinn::Connection, bytes::Bytes>;

/// # Cancel safety
///
/// cancel-safe: the h3 state is owned by this future and dropped with it.
async fn authenticate(connection: quinn::Connection) -> io::Result<H3Server> {
    let mut h3 = H3Server::new(h3_quinn::Connection::new(connection)).await.map_err(io::Error::other)?;
    let resolver = h3
        .accept()
        .await
        .map_err(io::Error::other)?
        .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "no auth request"))?;
    let (_request, mut stream) = resolver.resolve_request().await.map_err(io::Error::other)?;
    let response = http::Response::builder()
        .status(HYSTERIA_AUTH_STATUS)
        .header("Hysteria-UDP", "true")
        .body(())
        .map_err(io::Error::other)?;
    stream.send_response(response).await.map_err(io::Error::other)?;
    stream.finish().await.map_err(io::Error::other)?;
    Ok(h3)
}

/// # Cancel safety
///
/// cancel-safe: the stream halves are owned by this future and reset on drop.
async fn serve_tcp_stream(
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    events: mpsc::UnboundedSender<ServerEvent>,
    refuse_tcp: bool,
) -> io::Result<()> {
    if read_varint(&mut recv).await? != TCP_REQUEST_COMMAND {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected command"));
    }
    let address_len = usize::try_from(read_varint(&mut recv).await?).map_err(io::Error::other)?;
    let mut address = vec![0u8; address_len];
    recv.read_exact(&mut address).await.map_err(io::Error::other)?;
    let padding_len = usize::try_from(read_varint(&mut recv).await?).map_err(io::Error::other)?;
    let mut padding = vec![0u8; padding_len];
    recv.read_exact(&mut padding).await.map_err(io::Error::other)?;
    let _ = events.send(ServerEvent::TcpRequest(String::from_utf8_lossy(&address).into_owned()));

    let message: &[u8] = if refuse_tcp { b"connection refused" } else { b"" };
    let mut response = BytesMut::new();
    response.put_u8(u8::from(refuse_tcp));
    put_varint(message.len() as u64, &mut response);
    response.extend_from_slice(message);
    put_varint(0, &mut response);
    send.write_all(&response).await.map_err(io::Error::other)?;
    if refuse_tcp {
        send.finish().map_err(io::Error::other)?;
        return Ok(());
    }
    let _ = tokio::io::copy(&mut recv, &mut send).await;
    Ok(())
}
