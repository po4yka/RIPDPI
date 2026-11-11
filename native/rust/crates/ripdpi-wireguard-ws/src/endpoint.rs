// SPDX-License-Identifier: MIT

use std::io;
use std::sync::Arc;

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::{HeaderMap, HeaderValue};
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::{WebSocketStream, client_async_with_config};
use url::Url;

use crate::WsCarrier;
use crate::connect::{CarrierSocketProtector, connect_protected_carrier_host};

const DEFAULT_WSS_PORT: u16 = 443;
const BINARY_SUBPROTOCOL: &str = "binary";

/// A validated WireGuard-over-WebSocket WSS endpoint.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WssEndpoint {
    host: String,
    port: u16,
    path_and_query: String,
}

impl WssEndpoint {
    /// Parse and validate a production WSS endpoint URL.
    ///
    /// The WS carrier is meant to look like ordinary HTTPS WebSocket traffic, so
    /// the production endpoint is intentionally strict: only `wss://` is
    /// accepted, fragments/userinfo are rejected, and the request always carries
    /// the `binary` subprotocol used by the carrier.
    pub fn parse(input: &str) -> Result<Self, WssEndpointError> {
        let url = Url::parse(input).map_err(WssEndpointError::InvalidUrl)?;
        if url.scheme() != "wss" {
            return Err(WssEndpointError::UnsupportedScheme(url.scheme().to_string()));
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err(WssEndpointError::UserInfoUnsupported);
        }
        if url.fragment().is_some() {
            return Err(WssEndpointError::FragmentUnsupported);
        }
        let host = url
            .host_str()
            .ok_or(WssEndpointError::MissingHost)?
            .trim_start_matches('[')
            .trim_end_matches(']')
            .to_string();
        let port = url.port_or_known_default().unwrap_or(DEFAULT_WSS_PORT);
        let mut path_and_query = if url.path().is_empty() { "/".to_string() } else { url.path().to_string() };
        if let Some(query) = url.query() {
            path_and_query.push('?');
            path_and_query.push_str(query);
        }
        Ok(Self { host, port, path_and_query })
    }

    #[must_use]
    pub fn host(&self) -> &str {
        &self.host
    }

    #[must_use]
    pub fn port(&self) -> u16 {
        self.port
    }

    #[must_use]
    pub fn path_and_query(&self) -> &str {
        &self.path_and_query
    }

    #[must_use]
    pub fn request_uri(&self) -> String {
        format!("wss://{}{}", self.authority(), self.path_and_query)
    }

    #[must_use]
    pub fn host_header(&self) -> String {
        self.authority()
    }

    pub fn build_client_request(&self) -> Result<tokio_tungstenite::tungstenite::http::Request<()>, WssEndpointError> {
        let mut request = self.request_uri().into_client_request().map_err(WssEndpointError::InvalidRequest)?;
        let host = HeaderValue::from_str(&self.host_header()).map_err(WssEndpointError::InvalidHostHeader)?;
        request.headers_mut().insert("Host", host);
        request.headers_mut().insert("Sec-WebSocket-Protocol", HeaderValue::from_static(BINARY_SUBPROTOCOL));
        Ok(request)
    }

    fn authority(&self) -> String {
        let host = if self.host.contains(':') && !self.host.starts_with('[') {
            format!("[{}]", self.host)
        } else {
            self.host.clone()
        };
        if self.port == DEFAULT_WSS_PORT { host } else { format!("{host}:{}", self.port) }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum WssEndpointError {
    #[error("invalid WSS carrier endpoint URL: {0}")]
    InvalidUrl(#[from] url::ParseError),
    #[error("WS carrier endpoint must use wss scheme, got {0}")]
    UnsupportedScheme(String),
    #[error("WS carrier endpoint is missing a host")]
    MissingHost,
    #[error("WS carrier endpoint must not include user info")]
    UserInfoUnsupported,
    #[error("WS carrier endpoint must not include a fragment")]
    FragmentUnsupported,
    #[error("invalid WSS carrier request: {0}")]
    InvalidRequest(tokio_tungstenite::tungstenite::Error),
    #[error("invalid WSS carrier Host header: {0}")]
    InvalidHostHeader(tokio_tungstenite::tungstenite::http::header::InvalidHeaderValue),
    #[error("carrier WSS endpoint did not negotiate binary subprotocol")]
    BinarySubprotocolNotNegotiated,
}

pub type WssCarrierStream = WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;
pub type WssCarrier = WsCarrier<WssCarrierStream>;

/// Open a WSS carrier with the WebSocket library's default receive limits.
///
/// # Cancel safety:
/// cancel-safe: cancellation drops the in-flight connection before publishing it.
pub async fn connect_wss_carrier<P>(endpoint: &WssEndpoint, protector: &P) -> io::Result<WssCarrier>
where
    P: CarrierSocketProtector + ?Sized,
{
    connect_wss_carrier_with_config(endpoint, protector, None).await
}

/// Open a protected WSS carrier with an explicit receive datagram limit.
///
/// The frame limit bounds payload allocation; the message limit bounds aggregate
/// reassembly before delivery. It does not limit outbound datagrams. Callers own
/// the send buffer contract. A zero limit is rejected before network work.
///
/// # Cancel safety:
/// cancel-safe: cancellation drops the in-flight connection before publishing it.
pub async fn connect_wss_carrier_with_receive_limit<P>(
    endpoint: &WssEndpoint,
    protector: &P,
    max_datagram_size: usize,
) -> io::Result<WssCarrier>
where
    P: CarrierSocketProtector + ?Sized,
{
    let config = receive_config(max_datagram_size)?;
    connect_wss_carrier_with_config(endpoint, protector, Some(config)).await
}

fn receive_config(max_datagram_size: usize) -> io::Result<WebSocketConfig> {
    if max_datagram_size == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "carrier receive limit must be positive"));
    }
    Ok(WebSocketConfig::default().max_frame_size(Some(max_datagram_size)).max_message_size(Some(max_datagram_size)))
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the in-flight TCP/TLS/WS stream.
async fn connect_wss_carrier_with_config<P>(
    endpoint: &WssEndpoint,
    protector: &P,
    config: Option<WebSocketConfig>,
) -> io::Result<WssCarrier>
where
    P: CarrierSocketProtector + ?Sized,
{
    let stream = connect_protected_carrier_host(endpoint.host(), endpoint.port(), protector).await?;
    stream.set_nodelay(true)?;
    let request =
        endpoint.build_client_request().map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let server_name = ServerName::try_from(endpoint.host.clone())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid carrier TLS SNI: {error}")))?;
    let tls = default_tls_connector().connect(server_name, stream).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("carrier WSS TLS handshake failed: {error}"))
    })?;
    let (ws, response) = client_async_with_config(request, tls, config)
        .await
        .map_err(|error| io::Error::other(format!("carrier WSS handshake failed: {error}")))?;
    validate_binary_subprotocol(response.headers())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
    Ok(WsCarrier::new(ws))
}

fn validate_binary_subprotocol(headers: &HeaderMap<HeaderValue>) -> Result<(), WssEndpointError> {
    match headers.get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()) {
        Some(BINARY_SUBPROTOCOL) => Ok(()),
        _ => Err(WssEndpointError::BinarySubprotocolNotNegotiated),
    }
}

fn default_tls_connector() -> TlsConnector {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let builder = ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc-rs provider supports default TLS versions");
    // Infallible: the store is seeded from webpki_roots::TLS_SERVER_ROOTS and no CRLs are set.
    let mut config = ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
        .expect("bundled webpki roots are non-empty")
        .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    TlsConnector::from(Arc::new(config))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_requires_wss_scheme() {
        let err = WssEndpoint::parse("ws://carrier.example.test/wg").expect_err("plain ws rejected");
        assert!(matches!(err, WssEndpointError::UnsupportedScheme(_)));
    }

    #[test]
    fn endpoint_builds_real_wss_upgrade_request() {
        let endpoint = WssEndpoint::parse("wss://carrier.example.test:8443/wg?profile=a").expect("endpoint");
        let request = endpoint.build_client_request().expect("request");

        assert_eq!(endpoint.host(), "carrier.example.test");
        assert_eq!(endpoint.port(), 8443);
        assert_eq!(endpoint.path_and_query(), "/wg?profile=a");
        assert_eq!(request.uri().to_string(), "wss://carrier.example.test:8443/wg?profile=a");
        assert_eq!(
            request.headers().get("Host").and_then(|value| value.to_str().ok()),
            Some("carrier.example.test:8443"),
        );
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some(BINARY_SUBPROTOCOL),
        );
    }

    #[test]
    fn default_port_is_not_serialized_into_host_header() {
        let endpoint = WssEndpoint::parse("wss://carrier.example.test/wg").expect("endpoint");
        let request = endpoint.build_client_request().expect("request");

        assert_eq!(endpoint.port(), DEFAULT_WSS_PORT);
        assert_eq!(request.uri().to_string(), "wss://carrier.example.test/wg");
        assert_eq!(request.headers().get("Host").and_then(|value| value.to_str().ok()), Some("carrier.example.test"));
    }

    #[test]
    fn endpoint_supports_ipv6_authority() {
        let endpoint = WssEndpoint::parse("wss://[::1]:9443/wg").expect("endpoint");
        let request = endpoint.build_client_request().expect("request");

        assert_eq!(endpoint.host(), "::1");
        assert_eq!(request.uri().to_string(), "wss://[::1]:9443/wg");
        assert_eq!(request.headers().get("Host").and_then(|value| value.to_str().ok()), Some("[::1]:9443"));
    }

    #[test]
    fn response_must_negotiate_binary_subprotocol() {
        let mut headers = HeaderMap::new();
        assert!(matches!(validate_binary_subprotocol(&headers), Err(WssEndpointError::BinarySubprotocolNotNegotiated)));

        headers.insert("Sec-WebSocket-Protocol", HeaderValue::from_static("json"));
        assert!(matches!(validate_binary_subprotocol(&headers), Err(WssEndpointError::BinarySubprotocolNotNegotiated)));

        headers.insert("Sec-WebSocket-Protocol", HeaderValue::from_static(BINARY_SUBPROTOCOL));
        assert!(validate_binary_subprotocol(&headers).is_ok());
    }
    /// # Cancel safety:
    /// cancel-safe: the test owns and drops both stream ends.
    async fn read_wire_with_limit(
        wire: &[u8],
        limit: usize,
    ) -> Result<tokio_tungstenite::tungstenite::Message, tokio_tungstenite::tungstenite::Error> {
        use futures::StreamExt;
        use tokio::io::AsyncWriteExt;
        use tokio_tungstenite::tungstenite::protocol::Role;
        let (local, mut peer) = tokio::io::duplex(64);
        let mut ws =
            WebSocketStream::from_raw_socket(local, Role::Client, Some(receive_config(limit).expect("receive config")))
                .await;
        peer.write_all(wire).await.expect("literal peer frames");
        tokio::time::timeout(std::time::Duration::from_secs(1), ws.next())
            .await
            .expect("read must not wait for an oversized payload")
            .expect("message result")
    }

    /// # Cancel safety:
    /// cancel-safe: invalid input opens no stream.
    #[tokio::test]
    async fn zero_receive_limit_is_rejected_before_network_work() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("owned peer");
        let endpoint = WssEndpoint::parse(&format!("wss://{}/wg", listener.local_addr().expect("peer address")))
            .expect("endpoint");
        let result = tokio::time::timeout(
            std::time::Duration::from_millis(250),
            connect_wss_carrier_with_receive_limit(&endpoint, &|_| Ok(()), 0),
        )
        .await
        .expect("invalid limit must fail before a TLS exchange");
        let Err(error) = result else { panic!("zero receive limit must fail") };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(tokio::time::timeout(std::time::Duration::from_millis(25), listener.accept()).await.is_err());
    }

    /// # Cancel safety:
    /// cancel-safe: the helper owns both stream ends.
    #[tokio::test]
    async fn receive_limit_accepts_the_exact_frame_boundary() {
        let message = read_wire_with_limit(&[0x82, 8, 0, 1, 2, 3, 4, 5, 6, 7], 8).await.expect("boundary frame");
        assert_eq!(message.into_data().as_ref(), &[0, 1, 2, 3, 4, 5, 6, 7]);
    }

    /// # Cancel safety:
    /// cancel-safe: the helper owns both stream ends.
    #[tokio::test]
    async fn receive_limit_rejects_an_oversized_frame_before_its_payload() {
        assert!(matches!(
            read_wire_with_limit(&[0x82, 9], 8).await,
            Err(tokio_tungstenite::tungstenite::Error::Capacity(_))
        ));
    }

    /// # Cancel safety:
    /// cancel-safe: each helper call owns both stream ends.
    #[tokio::test]
    async fn receive_limit_counts_the_complete_fragmented_message() {
        let valid = [0x02, 4, 0, 1, 2, 3, 0x80, 4, 4, 5, 6, 7];
        let message = read_wire_with_limit(&valid, 8).await.expect("aggregate boundary");
        assert_eq!(message.into_data().as_ref(), &[0, 1, 2, 3, 4, 5, 6, 7]);
        let oversized = [0x02, 4, 0, 1, 2, 3, 0x80, 5, 4, 5, 6, 7, 8];
        assert!(matches!(
            read_wire_with_limit(&oversized, 8).await,
            Err(tokio_tungstenite::tungstenite::Error::Capacity(_))
        ));
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns both stream ends.
    #[tokio::test]
    async fn receive_limit_failure_ends_the_carrier_before_following_data() {
        use crate::WsCarrierError;
        use tokio::io::AsyncWriteExt;
        use tokio_tungstenite::tungstenite::protocol::Role;
        let (local, mut peer) = tokio::io::duplex(64);
        let ws = WebSocketStream::from_raw_socket(local, Role::Client, Some(receive_config(8).expect("config"))).await;
        let (_sender, mut receiver) = WsCarrier::new(ws).split();
        peer.write_all(&[0x82, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0x82, 1, 99]).await.expect("oversize then data");
        assert!(matches!(
            receiver.recv_datagram().await,
            Err(WsCarrierError::Transport(tokio_tungstenite::tungstenite::Error::Capacity(_)))
        ));
        assert!(matches!(receiver.recv_datagram().await, Err(WsCarrierError::Closed)));
    }
}
