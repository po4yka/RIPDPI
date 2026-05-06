use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use tungstenite::client::IntoClientRequest;
use tungstenite::WebSocket;

pub type WsOverTlsStream = WebSocket<StreamOwned<ClientConnection, TcpStream>>;

#[derive(Clone, Debug)]
pub struct WsOverTlsTarget {
    pub host: String,
    pub port: u16,
    pub path: String,
    pub resolved_addr: Option<SocketAddr>,
    pub connect_timeout: Option<Duration>,
}

impl WsOverTlsTarget {
    pub fn new(host: impl Into<String>, path: impl Into<String>) -> Self {
        Self { host: host.into(), port: 443, path: path.into(), resolved_addr: None, connect_timeout: None }
    }

    pub fn with_resolved_addr(mut self, resolved_addr: Option<SocketAddr>) -> Self {
        self.resolved_addr = resolved_addr;
        self
    }
}

#[derive(Clone, Debug, Default)]
pub struct WsOverTlsConnector;

impl WsOverTlsConnector {
    pub fn probe(&self, target: &WsOverTlsTarget) -> io::Result<()> {
        let _stream = self.connect(target)?;
        Ok(())
    }

    pub fn connect(&self, target: &WsOverTlsTarget) -> io::Result<WsOverTlsStream> {
        let socket_addr = resolve_target_addr(target)?;
        let tcp = connect_tcp(socket_addr, target.connect_timeout)?;
        let tls = connect_tls(tcp, target.host.as_str())?;
        let request = build_ws_request(target)?;
        let (ws, _response) = tungstenite::client(request, tls)
            .map_err(|err| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {err}")))?;
        Ok(ws)
    }
}

fn resolve_target_addr(target: &WsOverTlsTarget) -> io::Result<SocketAddr> {
    if let Some(addr) = target.resolved_addr {
        return Ok(addr);
    }
    (target.host.as_str(), target.port)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "WS over TLS target resolved no addresses"))
}

fn connect_tcp(addr: SocketAddr, timeout: Option<Duration>) -> io::Result<TcpStream> {
    let stream = match timeout {
        Some(timeout) => TcpStream::connect_timeout(&addr, timeout),
        None => TcpStream::connect(addr),
    }
    .map_err(|err| io::Error::new(err.kind(), format!("WS over TLS TCP connect to {addr}: {err}")))?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(timeout)?;
    stream.set_write_timeout(timeout)?;
    Ok(stream)
}

fn connect_tls(tcp: TcpStream, host: &str) -> io::Result<StreamOwned<ClientConnection, TcpStream>> {
    let server_name = ServerName::try_from(host.to_string())
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    let connection = ClientConnection::new(default_tls_config(), server_name)
        .map_err(|err| io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS setup: {err}")))?;
    let mut tls = StreamOwned::new(connection, tcp);
    while tls.conn.is_handshaking() {
        tls.conn
            .complete_io(&mut tls.sock)
            .map_err(|err| io::Error::new(err.kind(), format!("TLS handshake: {err}")))?;
    }
    Ok(tls)
}

fn default_tls_config() -> Arc<ClientConfig> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    Arc::new(
        ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_root_certificates(roots)
            .with_no_client_auth(),
    )
}

fn build_ws_request(target: &WsOverTlsTarget) -> io::Result<tungstenite::http::Request<()>> {
    let path = if target.path.starts_with('/') { target.path.clone() } else { format!("/{}", target.path) };
    let url = format!("wss://{}{}", target.host, path);
    let mut request = url.into_client_request().map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err))?;
    request.headers_mut().insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));
    Ok(request)
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{IpAddr, Ipv4Addr};

    #[test]
    fn target_defaults_to_standard_wss_port() {
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws");

        assert_eq!(target.port, 443);
        assert_eq!(target.path, "/apiws");
        assert_eq!(target.resolved_addr, None);
    }

    #[test]
    fn target_accepts_pre_resolved_addr() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 443);
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "/apiws").with_resolved_addr(Some(addr));

        assert_eq!(resolve_target_addr(&target).expect("resolved addr"), addr);
    }

    #[test]
    fn ws_request_uses_binary_subprotocol() {
        let target = WsOverTlsTarget::new("kws2.web.telegram.org", "apiws");
        let request = build_ws_request(&target).expect("request");

        assert_eq!(request.uri().to_string(), "wss://kws2.web.telegram.org/apiws");
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some("binary"),
        );
    }
}
