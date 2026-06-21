// SPDX-License-Identifier: MIT

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::{WebSocketStream, client_async};
use url::Url;

use crate::WsCarrier;
use crate::connect::{CarrierSocketProtector, connect_protected_carrier};

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
        let host = url.host_str().ok_or(WssEndpointError::MissingHost)?.to_string();
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
}

pub type WssCarrierStream = WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;
pub type WssCarrier = WsCarrier<WssCarrierStream>;

/// Open a production WSS WireGuard carrier over a protected TCP socket.
pub async fn connect_wss_carrier<P>(target: SocketAddr, endpoint: &WssEndpoint, protector: &P) -> io::Result<WssCarrier>
where
    P: CarrierSocketProtector + ?Sized,
{
    let stream = connect_protected_carrier(target, protector).await?;
    stream.set_nodelay(true)?;
    let request =
        endpoint.build_client_request().map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let server_name = ServerName::try_from(endpoint.host.clone())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid carrier TLS SNI: {error}")))?;
    let tls = default_tls_connector().connect(server_name, stream).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("carrier WSS TLS handshake failed: {error}"))
    })?;
    let (ws, _response) = client_async(request, tls)
        .await
        .map_err(|error| io::Error::other(format!("carrier WSS handshake failed: {error}")))?;
    Ok(WsCarrier::new(ws))
}

fn default_tls_connector() -> TlsConnector {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc-rs provider supports default TLS versions")
        .with_root_certificates(roots)
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
}
