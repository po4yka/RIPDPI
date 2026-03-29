use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
#[cfg(not(feature = "chrome-fingerprint"))]
use std::sync::Arc;
use std::time::Duration;

#[cfg(feature = "chrome-fingerprint")]
use boring::ssl::{SslConnector, SslMethod, SslStream};
#[cfg(not(feature = "chrome-fingerprint"))]
use rustls::pki_types::ServerName;
#[cfg(not(feature = "chrome-fingerprint"))]
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tungstenite::client::IntoClientRequest;
use tungstenite::WebSocket;

use crate::dc::{ws_host, ws_url, TelegramDc};
use crate::protect;

/// A connected WebSocket tunnel to a Telegram DC.
#[cfg(not(feature = "chrome-fingerprint"))]
type RustlsClientStream = StreamOwned<ClientConnection, TcpStream>;
#[cfg(not(feature = "chrome-fingerprint"))]
pub type WsStream = WebSocket<RustlsClientStream>;

/// A connected WebSocket tunnel to a Telegram DC (BoringSSL TLS backend).
///
/// When the `chrome-fingerprint` feature is enabled, TLS is handled by
/// BoringSSL (via the `boring` crate) instead of rustls. This produces a
/// ClientHello indistinguishable from Chrome, defeating JA3/JA4 fingerprinting.
#[cfg(feature = "chrome-fingerprint")]
pub type WsStream = WebSocket<SslStream<TcpStream>>;

/// Read timeout on the WebSocket's underlying TCP socket. The relay now owns
/// the WebSocket on one thread and uses this timeout as its I/O poll cadence
/// so outbound frames are scheduled promptly without busy-spinning.
pub(crate) const WS_READ_TIMEOUT: Duration = Duration::from_millis(10);

fn resolve_ws_target_with(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    mut resolve_socket_addrs: impl FnMut(&str) -> io::Result<SocketAddr>,
) -> io::Result<(String, SocketAddr)> {
    let host = ws_host(dc).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
        )
    })?;
    let target = match resolved_addr {
        Some(target) => target,
        None => resolve_socket_addrs(&format!("{host}:443"))?,
    };
    Ok((host, target))
}

fn resolve_ws_target(dc: TelegramDc, resolved_addr: Option<SocketAddr>) -> io::Result<(String, SocketAddr)> {
    resolve_ws_target_with(dc, resolved_addr, |addr| {
        addr.to_socket_addrs()?.next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, format!("WS tunnel resolved no address: {addr}"))
        })
    })
}

fn connect_tcp_socket_with(
    target: SocketAddr,
    protect_path: Option<&str>,
    mut protect_socket: impl FnMut(&Socket, &str) -> io::Result<()>,
) -> io::Result<TcpStream> {
    let domain = match target {
        std::net::SocketAddr::V4(_) => Domain::IPV4,
        std::net::SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;

    if let Some(path) = protect_path {
        protect_socket(&socket, path)?;
    }
    socket
        .connect(&SockAddr::from(target))
        .map_err(|e| io::Error::new(e.kind(), format!("WS tunnel TCP connect to {target}: {e}")))?;
    let tcp: TcpStream = socket.into();
    tcp.set_nodelay(true)?;

    // Keep WS reads on a short cadence so the relay can drain queued outbound
    // frames promptly even when the peer is idle on downlink.
    tcp.set_read_timeout(Some(WS_READ_TIMEOUT))?;
    Ok(tcp)
}

fn connect_tcp_socket(target: SocketAddr, protect_path: Option<&str>) -> io::Result<TcpStream> {
    connect_tcp_socket_with(target, protect_path, protect::protect_socket)
}

fn build_ws_request(url: &str) -> io::Result<tungstenite::http::Request<()>> {
    let mut request = url.into_client_request().map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
    request.headers_mut().insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));
    Ok(request)
}

#[cfg(not(feature = "chrome-fingerprint"))]
fn default_root_store() -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    roots
}

#[cfg(not(feature = "chrome-fingerprint"))]
fn build_rustls_client_config(roots: RootCertStore) -> Arc<ClientConfig> {
    let mut config = ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(config)
}

#[cfg(not(feature = "chrome-fingerprint"))]
fn connect_rustls_stream(host: &str, tcp: TcpStream) -> io::Result<RustlsClientStream> {
    connect_rustls_stream_with_config(host, tcp, build_rustls_client_config(default_root_store()))
}

#[cfg(not(feature = "chrome-fingerprint"))]
fn connect_rustls_stream_with_config(
    host: &str,
    tcp: TcpStream,
    config: Arc<ClientConfig>,
) -> io::Result<RustlsClientStream> {
    let server_name = ServerName::try_from(host.to_string())
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, format!("WS tunnel server name {host}: {err}")))?;
    let connection = ClientConnection::new(config, server_name)
        .map_err(|err| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS TLS setup: {err}")))?;
    let mut tls = StreamOwned::new(connection, tcp);

    while tls.conn.is_handshaking() {
        tls.conn
            .complete_io(&mut tls.sock)
            .map_err(|err| io::Error::new(err.kind(), format!("WS TLS handshake: {err}")))?;
    }

    Ok(tls)
}

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Establishes a TLS connection to `kws{dc}.web.telegram.org:443`, performs the
/// WebSocket handshake with `Sec-WebSocket-Protocol: binary`, and returns the
/// connected WebSocket.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
#[cfg(not(feature = "chrome-fingerprint"))]
pub fn open_ws_tunnel(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
) -> io::Result<WsStream> {
    let url = ws_url(dc).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
        )
    })?;
    let (host, target) = resolve_ws_target(dc, resolved_addr)?;
    let tcp = connect_tcp_socket(target, protect_path)?;
    let request = build_ws_request(url.as_str())?;
    let tls = connect_rustls_stream(&host, tcp)?;

    // WebSocket handshake over the pre-established rustls stream
    let (ws, _response) = tungstenite::client(request, tls)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {e}")))?;

    Ok(ws)
}

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Uses BoringSSL for TLS, producing a Chrome-compatible JA3/JA4 fingerprint
/// that is indistinguishable from real Chrome traffic to DPI systems.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
#[cfg(feature = "chrome-fingerprint")]
pub fn open_ws_tunnel(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
) -> io::Result<WsStream> {
    let url = ws_url(dc).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
        )
    })?;
    let (host, target) = resolve_ws_target(dc, resolved_addr)?;
    let tcp = connect_tcp_socket(target, protect_path)?;

    // BoringSSL TLS handshake -- produces Chrome-native cipher suite ordering,
    // GREASE values, and extension layout for DPI fingerprint evasion.
    let connector = SslConnector::builder(SslMethod::tls())
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("boring SSL builder: {e}")))?
        .build();
    let tls_stream = connector
        .connect(&host, tcp)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("boring TLS: {e}")))?;

    let request = build_ws_request(url.as_str())?;

    // WebSocket handshake over the pre-established BoringSSL stream
    let (ws, _response) = tungstenite::client(request, tls_stream)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {e}")))?;

    Ok(ws)
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::cell::Cell;
    use std::net::{Ipv4Addr, TcpListener};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::sync::Arc;
    use std::thread;

    #[cfg(not(feature = "chrome-fingerprint"))]
    use rcgen::generate_simple_self_signed;
    #[cfg(not(feature = "chrome-fingerprint"))]
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    #[cfg(not(feature = "chrome-fingerprint"))]
    use rustls::{ServerConfig, ServerConnection};

    #[test]
    fn build_ws_request_includes_binary_subprotocol() {
        let request = build_ws_request("wss://kws2.web.telegram.org/apiws").expect("build request");

        assert_eq!(request.uri().to_string(), "wss://kws2.web.telegram.org/apiws");
        assert_eq!(
            request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
            Some("binary"),
        );
    }

    #[test]
    fn connect_tcp_socket_protects_before_connecting() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let (tx, rx) = mpsc::channel();
        let accept_tx = tx.clone();
        let accept_thread = thread::spawn(move || {
            let (_stream, _peer) = listener.accept().expect("accept connection");
            accept_tx.send("accept").expect("record accept");
        });

        let stream = connect_tcp_socket_with(target, Some("/tmp/protect.sock"), |_, path| {
            assert_eq!(path, "/tmp/protect.sock");
            tx.send("protect").expect("record protect");
            Ok(())
        })
        .expect("connect socket");

        let events = [rx.recv().expect("first event"), rx.recv().expect("second event")];
        assert_eq!(events, ["protect", "accept"]);
        let timeout = stream.read_timeout().expect("read timeout").expect("timeout should be set");
        assert!(timeout >= WS_READ_TIMEOUT, "timeout {timeout:?} should be >= {WS_READ_TIMEOUT:?}");
        assert!(
            timeout <= WS_READ_TIMEOUT + Duration::from_millis(5),
            "timeout {timeout:?} too far from {WS_READ_TIMEOUT:?}",
        );
        assert!(stream.nodelay().expect("nodelay"));

        accept_thread.join().expect("join accept thread");
    }

    #[test]
    fn connect_tcp_socket_skips_protect_when_path_is_absent() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let called = Arc::new(AtomicBool::new(false));
        let called_flag = called.clone();
        let accept_thread = thread::spawn(move || listener.accept().expect("accept connection"));

        let _stream = connect_tcp_socket_with(target, None, |_, _| {
            called_flag.store(true, Ordering::SeqCst);
            Ok(())
        })
        .expect("connect socket");

        assert!(!called.load(Ordering::SeqCst));
        accept_thread.join().expect("join accept thread");
    }

    #[test]
    fn resolve_ws_target_uses_injected_addr_without_dns_lookup() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));
        let resolver_called = Cell::new(false);

        let (_host, resolved) = resolve_ws_target_with(TelegramDc::production(2), Some(target), |_| {
            resolver_called.set(true);
            Ok(target)
        })
        .expect("resolve target");

        assert_eq!(resolved, target);
        assert!(!resolver_called.get());
    }

    #[test]
    fn resolve_ws_target_uses_test_gateway_hostname() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));

        let (host, resolved) =
            resolve_ws_target_with(TelegramDc::from_raw(10_004).expect("test dc"), Some(target), |_| Ok(target))
                .expect("resolve target");

        assert_eq!(host, "kws4-test.web.telegram.org");
        assert_eq!(resolved, target);
    }

    #[test]
    fn resolve_ws_target_rejects_non_tunnelable_dc() {
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));
        let error = resolve_ws_target_with(TelegramDc::from_raw(-2).expect("media dc"), Some(target), |_| Ok(target))
            .expect_err("media dc should be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[cfg(not(feature = "chrome-fingerprint"))]
    fn localhost_server_config() -> (Arc<ServerConfig>, CertificateDer<'static>) {
        let certificate = generate_simple_self_signed(vec!["localhost".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
        let server_config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config");
        (Arc::new(server_config), certificate_der)
    }

    #[cfg(not(feature = "chrome-fingerprint"))]
    fn localhost_root_store(certificate_der: CertificateDer<'static>) -> RootCertStore {
        let mut roots = RootCertStore::empty();
        roots.add(certificate_der).expect("add localhost certificate");
        roots
    }

    #[cfg(not(feature = "chrome-fingerprint"))]
    #[test]
    fn connect_rustls_stream_completes_explicit_tls_handshake() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let (server_config, certificate_der) = localhost_server_config();
        let server_thread = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept connection");
            let connection = ServerConnection::new(server_config).expect("server connection");
            let mut tls = StreamOwned::new(connection, stream);
            while tls.conn.is_handshaking() {
                tls.conn.complete_io(&mut tls.sock).expect("server handshake");
            }
        });

        let tcp = connect_tcp_socket(target, None).expect("connect tcp socket");
        let tls_config = build_rustls_client_config(localhost_root_store(certificate_der));
        let tls = connect_rustls_stream_with_config("localhost", tcp, tls_config).expect("connect rustls stream");

        assert!(!tls.conn.is_handshaking(), "TLS handshake should complete before WebSocket setup");
        server_thread.join().expect("join server thread");
    }

    #[cfg(not(feature = "chrome-fingerprint"))]
    #[test]
    fn explicit_rustls_stream_supports_websocket_handshake() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let (server_config, certificate_der) = localhost_server_config();
        let server_thread = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept connection");
            let connection = ServerConnection::new(server_config).expect("server connection");
            let mut tls = StreamOwned::new(connection, stream);
            while tls.conn.is_handshaking() {
                tls.conn.complete_io(&mut tls.sock).expect("server handshake");
            }
            let _ws = tungstenite::accept_hdr(
                tls,
                #[allow(clippy::result_large_err)]
                |request: &tungstenite::handshake::server::Request,
                 mut response: tungstenite::handshake::server::Response| {
                    assert_eq!(
                        request.headers().get("Sec-WebSocket-Protocol").and_then(|value| value.to_str().ok()),
                        Some("binary"),
                    );
                    response
                        .headers_mut()
                        .insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));
                    Ok(response)
                },
            )
            .expect("accept websocket");
        });

        let tcp = connect_tcp_socket(target, None).expect("connect tcp socket");
        let tls_config = build_rustls_client_config(localhost_root_store(certificate_der));
        let tls = connect_rustls_stream_with_config("localhost", tcp, tls_config).expect("connect rustls stream");
        let request = build_ws_request("wss://localhost/apiws").expect("build ws request");
        let (_ws, response) = tungstenite::client(request, tls).expect("websocket handshake");

        assert_eq!(response.status(), tungstenite::http::StatusCode::SWITCHING_PROTOCOLS);
        server_thread.join().expect("join server thread");
    }
}
