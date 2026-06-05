//! Hysteria 2 protocol-server loopback fixture.
//!
//! A quinn-based server that speaks enough of the real Hysteria 2 protocol for
//! the throughput bench to drive the real `ripdpi-hysteria2` client through a
//! 1 MiB round-trip. Unlike `ripdpi-protocol-loopback`'s `QuicLoopback` (a
//! generic QUIC echo), this performs the Hysteria 2 handshake the client
//! requires:
//!
//! 1. QUIC + TLS with ALPN `h3` and a self-signed cert. The client trusts it via
//!    its runtime `insecure` config flag (`insecure=1` in the `hysteria2://`
//!    URL) — no TLS verification is relaxed on the server side.
//! 2. HTTP/3 auth: the client POSTs to `https://hysteria/auth`; the server
//!    answers HTTP status **233** (Hysteria's magic auth-ok status) with
//!    `Hysteria-UDP: false`, handled with the `h3` server crate on a clone of
//!    the QUIC connection. The h3 connection is held alive for the connection's
//!    lifetime (dropping it would close the shared QUIC connection).
//! 3. TCP proxy streams: after auth the client opens *raw* QUIC bidirectional
//!    streams carrying `[varint 0x401, varint addr_len, addr, varint pad_len,
//!    pad]`; the server replies `[status 0, varint 0, varint 0]` and echoes the
//!    stream. The h3 layer (auth) and the raw `accept_bi` loop (proxy) share one
//!    QUIC connection via a clone, sequenced by the client (auth before proxy).
//!
//! Dev/test fixture only — never a production Hysteria 2 server.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use rcgen::generate_simple_self_signed;
use tokio::io::AsyncReadExt;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

/// Hysteria 2's magic "auth accepted" HTTP status.
const HYSTERIA_AUTH_STATUS: u16 = 233;
/// Hysteria 2 TCP-request command code.
const TCP_REQUEST_COMMAND: u64 = 0x401;
/// Bound on the proxy-request address length, so a malformed peer can't make the
/// server allocate without limit.
const MAX_ADDRESS_LEN: u64 = 2048;

/// A loopback Hysteria 2 server. Authenticates any client (the auth token is not
/// validated — this is a throughput fixture, not an auth check) and echoes the
/// proxied TCP stream.
pub struct Hysteria2Loopback {
    local_addr: SocketAddr,
    endpoint: quinn::Endpoint,
    shutdown: Option<oneshot::Sender<()>>,
    join: Option<JoinHandle<()>>,
}

impl Hysteria2Loopback {
    /// Start the fixture on `127.0.0.1:0`.
    pub async fn start() -> io::Result<Self> {
        let endpoint = build_server_endpoint()?;
        let local_addr = endpoint.local_addr()?;

        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        let accept_endpoint = endpoint.clone();
        let join = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    incoming = accept_endpoint.accept() => {
                        let Some(incoming) = incoming else { break };
                        tokio::spawn(async move {
                            let _ = handle_connection(incoming).await;
                        });
                    }
                }
            }
        });

        Ok(Self { local_addr, endpoint, shutdown: Some(shutdown_tx), join: Some(join) })
    }

    /// The loopback address the server is listening on.
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// The UDP port the server is listening on.
    pub fn port(&self) -> u16 {
        self.local_addr.port()
    }

    /// Stop the accept loop and close the endpoint.
    pub async fn shutdown(mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        if let Some(join) = self.join.take() {
            let _ = join.await;
        }
        self.endpoint.close(0u32.into(), b"shutdown");
    }
}

impl Drop for Hysteria2Loopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        self.endpoint.close(0u32.into(), b"drop");
    }
}

fn build_server_endpoint() -> io::Result<quinn::Endpoint> {
    let cert = generate_simple_self_signed(vec!["localhost".to_string(), "hysteria".to_string()])
        .map_err(|error| io::Error::other(error.to_string()))?;
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());

    let mut server_tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .map_err(|error| io::Error::other(error.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der.into())
        .map_err(|error| io::Error::other(error.to_string()))?;
    // The Hysteria 2 client negotiates ALPN "h3" (it authenticates over HTTP/3).
    server_tls.alpn_protocols = vec![b"h3".to_vec()];

    let quic_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_tls)
        .map_err(|error| io::Error::other(error.to_string()))?;
    let mut server_cfg = quinn::ServerConfig::with_crypto(Arc::new(quic_crypto));
    let mut transport = quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().expect("valid idle timeout")));
    server_cfg.transport = Arc::new(transport);

    quinn::Endpoint::server(server_cfg, (Ipv4Addr::LOCALHOST, 0).into())
        .map_err(|error| io::Error::other(error.to_string()))
}

async fn handle_connection(incoming: quinn::Incoming) -> io::Result<()> {
    let connection = incoming.await.map_err(io::Error::other)?;

    // HTTP/3 auth on a clone of the connection. The returned h3 connection is
    // held ALIVE for the rest of the function: dropping `h3::server::Connection`
    // closes the underlying (shared) QUIC connection with H3_NO_ERROR, which
    // would tear down the raw proxy streams too.
    let _h3_conn = handle_h3_auth(connection.clone()).await?;

    // Raw proxy streams. The client serializes: auth fully completes before it
    // opens the first proxy stream, so these are not raced with the h3 accept.
    while let Ok((send, recv)) = connection.accept_bi().await {
        tokio::spawn(async move {
            let _ = handle_proxy_stream(send, recv).await;
        });
    }
    Ok(())
}

type H3ServerConnection = h3::server::Connection<h3_quinn::Connection, bytes::Bytes>;

async fn handle_h3_auth(connection: quinn::Connection) -> io::Result<H3ServerConnection> {
    let mut h3_conn = H3ServerConnection::new(h3_quinn::Connection::new(connection))
        .await
        .map_err(|error| io::Error::other(format!("h3 connection: {error}")))?;

    match h3_conn.accept().await {
        Ok(Some(resolver)) => {
            let (_request, mut stream) =
                resolver.resolve_request().await.map_err(|error| io::Error::other(format!("h3 request: {error}")))?;
            let response = http::Response::builder()
                .status(HYSTERIA_AUTH_STATUS)
                .header("Hysteria-UDP", "false")
                .body(())
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
            stream.send_response(response).await.map_err(|error| io::Error::other(format!("h3 response: {error}")))?;
            stream.finish().await.map_err(|error| io::Error::other(format!("h3 finish: {error}")))?;
            Ok(h3_conn)
        }
        Ok(None) => Err(io::Error::new(io::ErrorKind::UnexpectedEof, "h3 connection closed before auth request")),
        Err(error) => Err(io::Error::other(format!("h3 accept: {error}"))),
    }
}

async fn handle_proxy_stream(mut send: quinn::SendStream, mut recv: quinn::RecvStream) -> io::Result<()> {
    // Parse the TCP request frame: [varint cmd, varint addr_len, addr, varint
    // pad_len, pad]. We only need to consume it to stay framed; the bench echoes
    // bytes back to the client regardless of the requested target.
    let command = read_quic_varint(&mut recv).await?;
    if command != TCP_REQUEST_COMMAND {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected Hysteria2 command"));
    }
    let address_len = read_quic_varint(&mut recv).await?;
    if address_len > MAX_ADDRESS_LEN {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Hysteria2 address too long"));
    }
    let mut address = vec![0u8; address_len as usize];
    recv.read_exact(&mut address).await.map_err(io::Error::other)?;
    let padding_len = read_quic_varint(&mut recv).await?;
    if padding_len > 0 {
        let mut padding = vec![0u8; padding_len as usize];
        recv.read_exact(&mut padding).await.map_err(io::Error::other)?;
    }

    // Reply OK: status byte 0, empty message (varint 0), empty padding (varint 0).
    send.write_all(&[0x00, 0x00, 0x00]).await.map_err(io::Error::other)?;

    // Echo the proxied payload back to the client until it closes its send side.
    let _ = tokio::io::copy(&mut recv, &mut send).await;
    Ok(())
}

/// Read a QUIC (RFC 9000) variable-length integer from an async stream.
async fn read_quic_varint<R: tokio::io::AsyncRead + Unpin>(reader: &mut R) -> io::Result<u64> {
    let first = reader.read_u8().await?;
    let extra = 1usize << (first >> 6); // total length: 1, 2, 4, or 8 bytes
    let mut value = u64::from(first & 0x3f);
    for _ in 1..extra {
        value = (value << 8) | u64::from(reader.read_u8().await?);
    }
    Ok(value)
}
