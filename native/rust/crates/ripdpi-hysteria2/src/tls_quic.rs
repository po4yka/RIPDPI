use std::fmt;
use std::io;
use std::sync::Arc;

use http::Request;
use rand::RngExt;
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::quic_transport::{self, QuicTransportConfig};
use crate::salamander::SalamanderUdpSocket;

const HYSTERIA_AUTH_STATUS: u16 = 233;

pub(crate) struct H3ConnectionGuard {
    send_request: h3::client::SendRequest<h3_quinn::OpenStreams, bytes::Bytes>,
    _driver: DriverAbortGuard,
}

struct DriverAbortGuard(JoinHandle<()>);

impl Drop for DriverAbortGuard {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[derive(Clone)]
pub(crate) struct ClientSocketSpec {
    pub(crate) ipv6: bool,
    pub(crate) bind_low_port: bool,
    pub(crate) salamander_key: Option<String>,
    pub(crate) socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

impl fmt::Debug for ClientSocketSpec {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ClientSocketSpec")
            .field("ipv6", &self.ipv6)
            .field("bind_low_port", &self.bind_low_port)
            .field("salamander_key", &self.salamander_key.as_ref().map(|_| "<redacted>"))
            .field("socket_protection", &self.socket_protection)
            .finish()
    }
}

pub(crate) async fn authenticate_connection(
    config: &Config,
    connection: &quinn::Connection,
) -> Result<(bool, H3ConnectionGuard)> {
    let (mut h3_connection, send_request) = h3::client::new(h3_quinn::Connection::new(connection.clone())).await?;
    let driver = tokio::spawn(async move {
        let _ = std::future::poll_fn(|cx| h3_connection.poll_close(cx)).await;
    });
    let mut h3_guard = H3ConnectionGuard { send_request, _driver: DriverAbortGuard(driver) };
    let padding = generate_padding();
    let request = Request::builder()
        .method("POST")
        .uri("https://hysteria/auth")
        .header("Host", "hysteria")
        .header("Hysteria-Auth", &config.auth)
        .header("Hysteria-CC-RX", "0")
        .header("Hysteria-Padding", padding)
        .body(())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;

    let mut stream = h3_guard.send_request.send_request(request).await?;
    stream.finish().await?;
    let response = stream.recv_response().await?;

    if response.status().as_u16() != HYSTERIA_AUTH_STATUS {
        return Err(HysteriaError::AuthFailed);
    }

    let udp_supported = response.headers().get("Hysteria-UDP").and_then(|value| value.to_str().ok()) == Some("true");
    Ok((udp_supported, h3_guard))
}

pub(crate) fn build_endpoint(
    config: &Config,
    tls_config: rustls::ClientConfig,
    socket_spec: ClientSocketSpec,
) -> Result<(quinn::Endpoint, std::net::UdpSocket)> {
    let socket = build_client_udp_socket(&socket_spec)?;
    let socket_clone = socket.try_clone()?;
    let mut endpoint = if let Some(key) = config.salamander_key.as_ref() {
        let wrapped = SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?;
        quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            Arc::new(wrapped),
            Arc::new(quinn::TokioRuntime),
        )?
    } else {
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?
    };

    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?,
    )));
    Ok((endpoint, socket_clone))
}

/// Bind a UDP socket for the Hysteria2 QUIC client.
///
/// The address-family / low-port binding logic is shared with MASQUE and the
/// composable QUIC transport: this delegates to
/// [`quic_transport::build_client_udp_socket`] rather than carrying a private
/// copy. The `salamander_key` field of [`ClientSocketSpec`] is applied later
/// by [`build_endpoint`] / [`rebind_endpoint`], not here.
pub(crate) fn build_client_udp_socket(socket_spec: &ClientSocketSpec) -> io::Result<std::net::UdpSocket> {
    quic_transport::build_client_udp_socket_with_policy(
        socket_spec.ipv6,
        socket_spec.bind_low_port,
        socket_spec.socket_protection,
    )
}

pub(crate) fn rebind_endpoint(
    endpoint: &quinn::Endpoint,
    socket_spec: &ClientSocketSpec,
    socket: std::net::UdpSocket,
) -> io::Result<()> {
    if let Some(key) = socket_spec.salamander_key.as_ref() {
        endpoint.rebind_abstract(Arc::new(SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?))
    } else {
        endpoint.rebind(socket)
    }
}

/// Build the QUIC `rustls::ClientConfig` for a Hysteria2 profile.
///
/// This delegates to the shared [`QuicTransportConfig`] factory in
/// `crate::quic_transport` rather than hand-rolling the root-store / ALPN /
/// insecure-verifier wiring -- Hysteria2 now *consumes* the composable QUIC
/// transport's config factory instead of maintaining its own copy.
pub(crate) fn build_tls_config(config: &Config) -> Result<rustls::ClientConfig> {
    QuicTransportConfig::new(config.server_name.clone()).with_insecure(config.insecure).build_rustls_client_config()
}

fn generate_padding() -> String {
    const PADDING_CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::rng();
    let padding_len = rng.random_range(8..40);
    let mut padding = String::with_capacity(padding_len);
    for _ in 0..padding_len {
        let index = rng.random_range(0..PADDING_CHARS.len());
        padding.push(PADDING_CHARS[index] as char);
    }
    padding
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::sync::oneshot;

    use super::DriverAbortGuard;

    struct DropNotice(Option<oneshot::Sender<()>>);

    impl Drop for DropNotice {
        fn drop(&mut self) {
            if let Some(sender) = self.0.take() {
                let _ = sender.send(());
            }
        }
    }

    #[tokio::test]
    async fn driver_guard_aborts_pending_task_on_drop() {
        let (started_tx, started_rx) = oneshot::channel();
        let (dropped_tx, dropped_rx) = oneshot::channel();
        let driver = tokio::spawn(async move {
            let _drop_notice = DropNotice(Some(dropped_tx));
            let _ = started_tx.send(());
            std::future::pending::<()>().await;
        });
        started_rx.await.expect("driver task started");

        drop(DriverAbortGuard(driver));

        tokio::time::timeout(Duration::from_secs(1), dropped_rx)
            .await
            .expect("aborted driver must release its task state")
            .expect("driver drop notice");
    }
}
