use std::io;

#[cfg(test)]
use boring::ssl::SslVerifyMode;
use bytes::Bytes;
use hyper::ext::Protocol;
use hyper_util::rt::TokioIo;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

use crate::auth::AuthHeader;
use crate::capsule::{
    decode_connect_udp_payload, decode_datagram_capsules, encode_connect_udp_payload, encode_datagram_capsule,
    CapsuleError, CapsuleErrorKind,
};
use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{validate_connect_udp_response, validate_proxy_response, AttemptError};
use crate::tls::apply_h2_client_auth;
use crate::udp::{MasqueUdpFlow, MasqueUdpSender};
use crate::url::{build_connect_udp_path, parse_proxy_origin, resolve_proxy_socket_addr, ProxyOrigin, TargetAuthority};

pub(crate) fn encode_h2_datagram_capsule(payload: &[u8]) -> Result<Vec<u8>, CapsuleError> {
    encode_datagram_capsule(payload)
}

pub(crate) fn decode_h2_datagram_capsules(input: &[u8]) -> Result<Vec<Vec<u8>>, CapsuleError> {
    decode_datagram_capsules(input)
}

pub(crate) fn build_h2_connect_udp_request(
    proxy_origin: &ProxyOrigin,
    target: &TargetAuthority,
    auth_header: Option<&AuthHeader>,
) -> io::Result<hyper::Request<http_body_util::Empty<Bytes>>> {
    let request_uri = format!("https://{}{}", proxy_origin.authority, build_connect_udp_path(proxy_origin, target));
    let mut request = hyper::Request::builder().method("CONNECT").uri(request_uri).header("capsule-protocol", "?1");
    if let Some(header) = auth_header {
        request = request.header(header.name, header.value.as_str());
    }
    let mut request = request.body(http_body_util::Empty::<Bytes>::new()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT-UDP request: {error}"))
    })?;
    request.extensions_mut().insert(Protocol::from_static("connect-udp"));
    Ok(request)
}

pub(crate) async fn attempt_h2_connect_tcp(
    config: &MasqueConfig,
    _target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo, AttemptError> {
    let proxy_origin = parse_proxy_origin(config)?;
    let tcp = TcpStream::connect(resolve_proxy_socket_addr(&proxy_origin)?)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("failed to connect to MASQUE proxy: {error}")))?;
    tcp.set_nodelay(true)?;

    let mut connector_builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
    apply_h2_client_auth(&mut connector_builder, config)?;
    #[cfg(test)]
    relax_loopback_fixture_certificate_verification(&mut connector_builder, &proxy_origin.host);
    let connector = connector_builder.build();
    let mut ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("MASQUE H2 ECH: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, tcp).await.map_err(|error| {
        if config.ech_config.is_some() && error.ssl().is_some_and(|ssl| ssl.get_ech_retry_configs().is_some()) {
            return io::Error::new(
                io::ErrorKind::InvalidData,
                format!("MASQUE H2 ECH: {}", ripdpi_tls_profiles::EchOutboundError::RetryRequired),
            );
        }
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake failed: {error}"))
    })?;

    let io = TokioIo::new(tls);
    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), io).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate H2: {error}"))
        })?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "MASQUE H2 TCP driver closed");
        }
    });

    let request = apply_request_headers(
        hyper::Request::builder().method("CONNECT").uri(proxy_origin.request_uri),
        config,
        auth_header,
    )?
    .body(http_body_util::Empty::<Bytes>::new())
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT request: {error}")))?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H2 CONNECT request: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade H2 CONNECT stream: {error}"))
    })?;
    Ok(TokioIo::new(upgraded))
}

pub(crate) async fn attempt_h2_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = crate::url::parse_target(target)?;
    let target_label = target.authority();
    let proxy_origin = parse_proxy_origin(config)?;
    let tcp = TcpStream::connect(resolve_proxy_socket_addr(&proxy_origin)?)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("failed to connect to MASQUE proxy: {error}")))?;
    tcp.set_nodelay(true)?;

    let mut connector_builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
    apply_h2_client_auth(&mut connector_builder, config)?;
    #[cfg(test)]
    relax_loopback_fixture_certificate_verification(&mut connector_builder, &proxy_origin.host);
    let connector = connector_builder.build();
    let mut ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    ripdpi_tls_profiles::configure_boring_ech(&mut ssl, config.ech_config.as_ref())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("MASQUE H2 ECH: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, tcp).await.map_err(|error| {
        if config.ech_config.is_some() && error.ssl().is_some_and(|ssl| ssl.get_ech_retry_configs().is_some()) {
            return io::Error::new(
                io::ErrorKind::InvalidData,
                format!("MASQUE H2 ECH: {}", ripdpi_tls_profiles::EchOutboundError::RetryRequired),
            );
        }
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake failed: {error}"))
    })?;

    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), TokioIo::new(tls)).await.map_err(
            |error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate H2: {error}")),
        )?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "MASQUE H2 UDP driver closed");
        }
    });

    let request = build_h2_connect_udp_request(&proxy_origin, &target, auth_header)?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H2 CONNECT-UDP request: {error}"))
    })?;
    validate_connect_udp_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade H2 CONNECT-UDP stream: {error}"))
    })?;
    let (mut reader, mut writer) = tokio::io::split(TokioIo::new(upgraded));
    let (outgoing_tx, mut outgoing_rx) = mpsc::channel::<Vec<u8>>(64);
    let writer_task = tokio::spawn(async move {
        while let Some(payload) = outgoing_rx.recv().await {
            let datagram = match encode_connect_udp_payload(0, &payload)
                .and_then(|payload| encode_h2_datagram_capsule(&payload))
            {
                Ok(datagram) => datagram,
                Err(error) => {
                    tracing::debug!(error = %error, "failed to encode MASQUE H2 UDP capsule");
                    break;
                }
            };
            if let Err(error) = writer.write_all(&datagram).await {
                tracing::debug!(error = %error, "failed to write MASQUE H2 UDP capsule");
                break;
            }
        }
    });
    let reader_task = tokio::spawn(async move {
        let mut buffer = Vec::new();
        let mut chunk = [0u8; 4096];
        loop {
            let read = match reader.read(&mut chunk).await {
                Ok(0) => break,
                Ok(read) => read,
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "MASQUE H2 UDP capsule reader closed");
                    break;
                }
            };
            buffer.extend_from_slice(&chunk[..read]);
            match decode_h2_datagram_capsules(&buffer) {
                Ok(capsules) => {
                    buffer.clear();
                    for capsule in capsules {
                        match decode_connect_udp_payload(&capsule) {
                            Ok(Some((_, payload))) => {
                                if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                                    return;
                                }
                            }
                            Ok(None) => {
                                tracing::debug!(target = %target_label, "ignored MASQUE H2 UDP capsule for unsupported context id");
                            }
                            Err(error) => {
                                tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE H2 UDP capsule payload");
                            }
                        }
                    }
                }
                Err(error) if error.kind() == CapsuleErrorKind::Truncated => {}
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "failed to decode MASQUE H2 UDP capsules");
                    break;
                }
            }
        }
    });

    Ok(MasqueUdpFlow { sender: MasqueUdpSender::H2(outgoing_tx), driver_task: writer_task, reader_task })
}

#[cfg(test)]
fn relax_loopback_fixture_certificate_verification(builder: &mut boring::ssl::SslConnectorBuilder, proxy_host: &str) {
    if matches!(proxy_host, "127.0.0.1" | "localhost") {
        builder.set_verify(SslVerifyMode::NONE);
    }
}
