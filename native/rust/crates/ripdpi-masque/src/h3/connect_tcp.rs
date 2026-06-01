use std::future::poll_fn;
use std::io;

use http::Request;

use super::tcp_bridge::spawn_h3_bridge;
use super::transport::connect_h3_transport;
use crate::auth::AuthHeader;
use crate::client::AsyncIo;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{AttemptError, validate_proxy_response};
use crate::url::parse_proxy_origin;

pub(crate) async fn attempt_h3_connect_tcp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo + use<>, AttemptError> {
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
