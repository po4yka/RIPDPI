use std::future::poll_fn;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use bytes::{Buf, Bytes};
use h3_datagram::datagram_handler::HandleDatagramsExt;
use http::Request;
use rustls::RootCertStore;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

use crate::auth::AuthHeader;
use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{validate_proxy_response, AttemptError};
use crate::tls::load_client_identity;
use crate::udp::{MasqueUdpFlow, UDP_CONTEXT_ID};
use crate::url::{build_connect_udp_path, parse_proxy_origin, parse_target, resolve_proxy_socket_addr};

pub(crate) async fn attempt_h3_connect_tcp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo, AttemptError> {
    let proxy_origin = parse_proxy_origin(config)?;
    let (mut driver, mut send_request) = connect_h3_transport(config, false).await?;
    let request = Request::builder()
        .method("CONNECT")
        .uri(proxy_origin.request_uri)
        .header(":protocol", "connect-tcp")
        .header(":authority", target);
    let request = apply_request_headers(request, config, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-TCP request: {error}"))
    })?;

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-TCP request: {error}"))
    })?;
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-TCP response: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers(), config.effective_auth_mode())?;

    tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 TCP driver closed");
    });

    Ok(spawn_h3_bridge(stream))
}

pub(crate) async fn attempt_h3_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = parse_target(target)?;
    let proxy_origin = parse_proxy_origin(config)?;
    let (mut driver, mut send_request) = connect_h3_transport(config, true).await?;

    let request = Request::builder()
        .method("CONNECT")
        .uri(build_connect_udp_path(&proxy_origin, &target))
        .header(":protocol", "connect-udp")
        .header(":authority", proxy_origin.authority)
        .header(":scheme", "https")
        .header("capsule-protocol", "?1");
    let request = apply_request_headers(request, config, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-UDP request: {error}"))
    })?;

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-UDP request: {error}"))
    })?;
    stream.finish().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to finish H3 CONNECT-UDP request: {error}"))
    })?;
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-UDP response: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let stream_id = stream.id();
    let datagram_sender = driver.get_datagram_sender(stream_id);
    let mut datagram_reader = driver.get_datagram_reader();
    let target_label = target.authority();

    let reader_task = tokio::spawn(async move {
        let _stream = stream;
        loop {
            let datagram = match datagram_reader.read_datagram().await {
                Ok(datagram) => datagram,
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "MASQUE UDP datagram reader closed");
                    break;
                }
            };
            if datagram.stream_id() != stream_id {
                continue;
            }
            let payload = datagram.into_payload();
            match decode_udp_payload(payload) {
                Ok(payload) => {
                    if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                        break;
                    }
                }
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE UDP datagram");
                }
            }
        }
    });

    let driver_task = tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 UDP driver closed");
    });

    Ok(MasqueUdpFlow { sender: datagram_sender, driver_task, reader_task })
}

async fn connect_h3_transport(
    config: &MasqueConfig,
    enable_datagram: bool,
) -> Result<
    (h3::client::Connection<h3_quinn::Connection, Bytes>, h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>),
    AttemptError,
> {
    let proxy_origin = parse_proxy_origin(config)?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let tls_config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(roots);
    let mut tls_config = if let Some((certificates, private_key)) = load_client_identity(config)? {
        tls_config
            .with_client_auth_cert(certificates, private_key)
            .map_err(|error| io::Error::other(format!("failed to configure MASQUE client identity: {error}")))?
    } else {
        tls_config.with_no_client_auth()
    };
    tls_config.alpn_protocols = vec![b"h3".to_vec()];

    let quic_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::other(format!("failed to build QUIC TLS config: {error}")))?,
    ));

    let proxy_addr = resolve_proxy_socket_addr(&proxy_origin)?;
    let socket = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port)
        .map_err(|error| io::Error::other(format!("failed to bind QUIC client socket: {error}")))?;
    let mut endpoint =
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))
            .map_err(|error| io::Error::other(format!("failed to create QUIC client endpoint: {error}")))?;
    endpoint.set_default_client_config(quic_config);

    let connection = endpoint
        .connect(proxy_addr, &proxy_origin.host)
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC connect failed: {error}")))?
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC handshake failed: {error}")))?;
    maybe_rebind_quic_endpoint(config, &endpoint, proxy_addr)
        .map_err(|error| io::Error::other(format!("failed to rebind QUIC transport: {error}")))?;

    let mut builder = h3::client::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(enable_datagram);
    builder.build(h3_quinn::Connection::new(connection)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate HTTP/3: {error}")).into()
    })
}

fn build_client_udp_socket(ipv6: bool, bind_low_port: bool) -> io::Result<std::net::UdpSocket> {
    let bind_addr =
        if ipv6 { SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)) } else { SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)) };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    Ok(socket.into())
}

fn try_bind_low_port(socket: &Socket, bind_ip: IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

fn maybe_rebind_quic_endpoint(
    config: &MasqueConfig,
    endpoint: &quinn::Endpoint,
    proxy_addr: SocketAddr,
) -> io::Result<()> {
    if !config.quic_migrate_after_handshake {
        return Ok(());
    }
    let replacement = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port)?;
    endpoint.rebind(replacement)
}

pub(crate) fn decode_udp_payload(payload: Bytes) -> io::Result<Vec<u8>> {
    let Some((&context_id, payload)) = payload.split_first() else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "MASQUE UDP datagram is missing the required context identifier",
        ));
    };
    if context_id != UDP_CONTEXT_ID {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported MASQUE UDP context identifier {context_id}"),
        ));
    }
    Ok(payload.to_vec())
}

fn spawn_h3_bridge(mut stream: h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>) -> impl AsyncIo {
    let (app_io, bridge_io) = tokio::io::duplex(64 * 1024);
    let (mut bridge_reader, mut bridge_writer) = tokio::io::split(bridge_io);

    tokio::spawn(async move {
        let mut send_buffer = vec![0u8; 16 * 1024];
        loop {
            tokio::select! {
                received = stream.recv_data() => {
                    match received {
                        Ok(Some(mut data)) => {
                            let bytes = data.copy_to_bytes(data.remaining());
                            if let Err(error) = bridge_writer.write_all(&bytes).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge writer closed");
                                break;
                            }
                        }
                        Ok(None) => break,
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge recv error");
                            break;
                        }
                    }
                }
                read = bridge_reader.read(&mut send_buffer) => {
                    match read {
                        Ok(0) => break,
                        Ok(count) => {
                            if let Err(error) = stream.send_data(Bytes::copy_from_slice(&send_buffer[..count])).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge send error");
                                break;
                            }
                        }
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge reader closed");
                            break;
                        }
                    }
                }
            }
        }
        let _ = stream.finish().await;
    });

    app_io
}
