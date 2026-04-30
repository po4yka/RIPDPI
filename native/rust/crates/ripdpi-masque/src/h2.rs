use std::io;

use bytes::Bytes;
use hyper_util::rt::TokioIo;
use tokio::net::TcpStream;

use crate::auth::AuthHeader;
use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{validate_proxy_response, AttemptError};
use crate::tls::apply_h2_client_auth;
use crate::url::{parse_proxy_origin, resolve_proxy_socket_addr};

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
    let connector = connector_builder.build();
    let ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, tcp).await.map_err(|error| {
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
