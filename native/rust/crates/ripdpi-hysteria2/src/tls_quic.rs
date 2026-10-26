use std::fmt;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use http::Request;
use rand::RngExt;
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::hopping_socket::HoppingSocket;
use crate::quic_transport::{self, QuicTransportConfig};
use crate::salamander::SalamanderUdpSocket;

const HYSTERIA_AUTH_STATUS: u16 = 233;
/// Reference client values (apernet/hysteria `core/client/config.go`): a PING
/// every 10 s keeps quiet flows and mobile UDP NAT mappings alive, and a
/// dead path is detected after 30 s without packets.
const KEEP_ALIVE_INTERVAL: std::time::Duration = std::time::Duration::from_secs(10);
const MAX_IDLE_TIMEOUT: quinn::VarInt = quinn::VarInt::from_u32(30_000);
/// Reference receive windows (8 MiB per stream, 20 MiB per connection).
/// quinn's 1.25 MB stream default caps one download near 50 Mbit/s at
/// 200 ms RTT.
const STREAM_RECEIVE_WINDOW: quinn::VarInt = quinn::VarInt::from_u32(8 * 1024 * 1024);
const CONNECTION_RECEIVE_WINDOW: quinn::VarInt = quinn::VarInt::from_u32(20 * 1024 * 1024);

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
    server_addr: SocketAddr,
) -> Result<(quinn::Endpoint, Option<std::net::UdpSocket>, Option<Arc<HoppingSocket>>)> {
    let socket = build_client_udp_socket(&socket_spec)?;
    let socket_clone = if config.port_hopping.is_none() { Some(socket.try_clone()?) } else { None };
    let mut hopping_socket = None;
    let mut endpoint = if let Some(policy) = &config.port_hopping {
        let wrapped = Arc::new(HoppingSocket::new(server_addr, policy.clone(), socket_spec, socket)?);
        let endpoint = quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            wrapped.clone(),
            Arc::new(quinn::TokioRuntime),
        )?;
        hopping_socket = Some(wrapped);
        endpoint
    } else if let Some(key) = config.salamander_key.as_ref() {
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

    let mut client_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?,
    ));
    client_config.transport_config(Arc::new(client_transport_config()));
    endpoint.set_default_client_config(client_config);
    Ok((endpoint, socket_clone, hopping_socket))
}

fn client_transport_config() -> quinn::TransportConfig {
    let mut transport = quinn::TransportConfig::default();
    transport.keep_alive_interval(Some(KEEP_ALIVE_INTERVAL));
    transport.max_idle_timeout(Some(MAX_IDLE_TIMEOUT.into()));
    transport.stream_receive_window(STREAM_RECEIVE_WINDOW);
    transport.receive_window(CONNECTION_RECEIVE_WINDOW);
    transport
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
    hopping_socket: Option<&HoppingSocket>,
) -> io::Result<()> {
    if let Some(hopping) = hopping_socket {
        return hopping.rebind(socket);
    }
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
    QuicTransportConfig::new(config.server_name.clone())
        .with_insecure(config.insecure)
        .with_pin_sha256(config.pin_sha256)
        .build_rustls_client_config()
}

fn generate_padding() -> String {
    const PADDING_CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::rng();
    // Reference auth request padding range (`authRequestPadding`).
    let padding_len = rng.random_range(256..2048);
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

    /// Reference auth padding is 256..2048 characters (apernet/hysteria
    /// `core/internal/protocol/padding.go`); a shorter range separates this
    /// client from the reference by auth request size.
    #[test]
    fn auth_padding_uses_the_reference_length_range() {
        let lengths: Vec<usize> = (0..200).map(|_| super::generate_padding().len()).collect();

        assert!(lengths.iter().all(|length| (256..2048).contains(length)), "out-of-range padding: {lengths:?}");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn salamander_endpoint_builds_and_rebinds_blocking_socket() {
        let mut config =
            crate::config::Config::from_parts("fixture-auth".into(), "127.0.0.1", 443, "fixture.example".into())
                .expect("client config");
        config.salamander_key = Some("fixture-key".into());
        let socket_spec = super::ClientSocketSpec {
            ipv6: false,
            bind_low_port: false,
            salamander_key: config.salamander_key.clone(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        };
        let tls_config = super::build_tls_config(&config).expect("TLS config");
        let server_addr = "127.0.0.1:443".parse().expect("server address");
        let (endpoint, original_socket, hopping_socket) =
            super::build_endpoint(&config, tls_config, socket_spec.clone(), server_addr)
                .expect("initial Salamander endpoint");
        assert!(hopping_socket.is_none());
        let original_socket = original_socket.expect("non-hopping socket clone");
        assert_eq!(
            endpoint.local_addr().expect("initial endpoint address"),
            original_socket.local_addr().expect("original socket address"),
        );

        let rebound_socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind replacement socket");
        rebound_socket.set_nonblocking(false).expect("explicit blocking replacement");
        let rebound_addr = rebound_socket.local_addr().expect("replacement address");
        super::rebind_endpoint(&endpoint, &socket_spec, rebound_socket, None).expect("rebind Salamander endpoint");
        assert_eq!(endpoint.local_addr().expect("rebound endpoint address"), rebound_addr);
        endpoint.close(0u32.into(), b"test complete");
        tokio::time::timeout(Duration::from_secs(2), endpoint.wait_idle()).await.expect("endpoint driver stops");
    }

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
