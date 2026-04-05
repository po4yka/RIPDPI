use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use boring::ssl::SslStream;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tungstenite::client::IntoClientRequest;
use tungstenite::WebSocket;

use crate::dc::{ws_host, ws_url, TelegramDc};
use crate::protect;

/// A connected WebSocket tunnel to a Telegram DC (BoringSSL TLS backend).
///
/// TLS is handled by BoringSSL (via `ripdpi-tls-profiles`) to produce a
/// ClientHello indistinguishable from Chrome, defeating JA3/JA4 fingerprinting.
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
    connect_timeout: Option<Duration>,
    mut protect_socket: impl FnMut(&Socket, &str) -> io::Result<()>,
) -> io::Result<TcpStream> {
    connect_tcp_socket_with_impl(
        target,
        protect_path,
        connect_timeout,
        &mut protect_socket,
        |socket, target, timeout| match timeout {
            Some(timeout) => socket.connect_timeout(&SockAddr::from(target), timeout),
            None => socket.connect(&SockAddr::from(target)),
        },
    )
}

fn connect_tcp_socket_with_impl(
    target: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    mut protect_socket: impl FnMut(&Socket, &str) -> io::Result<()>,
    mut connect_socket: impl FnMut(&Socket, SocketAddr, Option<Duration>) -> io::Result<()>,
) -> io::Result<TcpStream> {
    let domain = match target {
        std::net::SocketAddr::V4(_) => Domain::IPV4,
        std::net::SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;

    if let Some(path) = protect_path {
        protect_socket(&socket, path)?;
    }
    connect_socket(&socket, target, connect_timeout)
        .map_err(|e| io::Error::new(e.kind(), format!("WS tunnel TCP connect to {target}: {e}")))?;
    let tcp: TcpStream = socket.into();
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

fn connect_tcp_socket(
    target: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    connect_tcp_socket_with(target, protect_path, connect_timeout, protect::protect_socket)
}

fn configure_bootstrap_socket(tcp: &TcpStream, connect_timeout: Option<Duration>) -> io::Result<()> {
    tcp.set_read_timeout(connect_timeout)?;
    tcp.set_write_timeout(connect_timeout)?;
    Ok(())
}

fn configure_relay_socket(tcp: &TcpStream) -> io::Result<()> {
    // The relay polls reads on a short cadence so queued outbound frames do not
    // wait indefinitely behind an idle downlink.
    tcp.set_read_timeout(Some(WS_READ_TIMEOUT))?;
    tcp.set_write_timeout(None)?;
    Ok(())
}

fn configure_established_ws_stream(ws: &mut WsStream) -> io::Result<()> {
    configure_relay_socket(ws.get_mut().get_ref())
}

fn build_ws_request(url: &str) -> io::Result<tungstenite::http::Request<()>> {
    let mut request = url.into_client_request().map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
    request.headers_mut().insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));
    Ok(request)
}

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Uses BoringSSL via `ripdpi-tls-profiles` for TLS, producing a Chrome-compatible
/// JA3/JA4 fingerprint that is indistinguishable from real Chrome traffic to DPI
/// systems.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
pub(crate) fn open_ws_tunnel_with_timeout(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
    fake_sni: Option<&str>,
) -> io::Result<WsStream> {
    let url = ws_url(dc).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("WS tunnel not supported for Telegram DC class {:?} raw={}", dc.class(), dc.raw()),
        )
    })?;
    let (host, target) = resolve_ws_target(dc, resolved_addr)?;
    let tls_host = fake_sni.unwrap_or(&host);
    let tcp = connect_tcp_socket(target, protect_path, connect_timeout)?;
    configure_bootstrap_socket(&tcp, connect_timeout)?;

    // BoringSSL TLS handshake -- produces Chrome-native cipher suite ordering,
    // GREASE values, and extension layout for DPI fingerprint evasion.
    let connector = ripdpi_tls_profiles::build_connector("chrome_stable", fake_sni.is_none())
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("TLS profile: {e}")))?;
    let tls_stream = connector
        .connect(tls_host, tcp)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("boring TLS: {e}")))?;

    let request = build_ws_request(url.as_str())?;

    // WebSocket handshake over the pre-established BoringSSL stream.
    let (mut ws, _response) = tungstenite::client(request, tls_stream)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {e}")))?;
    configure_established_ws_stream(&mut ws)?;

    Ok(ws)
}

pub fn open_ws_tunnel(
    dc: TelegramDc,
    resolved_addr: Option<SocketAddr>,
    protect_path: Option<&str>,
) -> io::Result<WsStream> {
    open_ws_tunnel_with_timeout(dc, resolved_addr, protect_path, None, None)
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

        let stream = connect_tcp_socket_with(target, Some("/tmp/protect.sock"), None, |_, path| {
            assert_eq!(path, "/tmp/protect.sock");
            tx.send("protect").expect("record protect");
            Ok(())
        })
        .expect("connect socket");

        let events = [rx.recv().expect("first event"), rx.recv().expect("second event")];
        assert_eq!(events, ["protect", "accept"]);
        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
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

        let _stream = connect_tcp_socket_with(target, None, None, |_, _| {
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

    #[test]
    fn connect_tcp_socket_passes_configured_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let accept_thread = thread::spawn(move || listener.accept().expect("accept connection"));
        let observed_timeout = Cell::new(None);
        let expected_timeout = Duration::from_millis(321);

        let stream = connect_tcp_socket_with_impl(
            target,
            None,
            Some(expected_timeout),
            |_socket, _path| unreachable!("protect should not run"),
            |socket, target, timeout| {
                observed_timeout.set(timeout);
                socket.connect_timeout(&SockAddr::from(target), timeout.expect("connect timeout"))
            },
        )
        .expect("connect socket");

        assert_eq!(observed_timeout.get(), Some(expected_timeout));
        assert!(stream.nodelay().expect("nodelay"));
        accept_thread.join().expect("join accept thread");
    }
}
