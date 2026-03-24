use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tungstenite::client::IntoClientRequest;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::WebSocket;

use crate::dc::ws_url;
use crate::protect;

/// A connected WebSocket tunnel to a Telegram DC.
pub type WsStream = WebSocket<MaybeTlsStream<TcpStream>>;

/// Read timeout on the WebSocket's underlying TCP socket. The relay now owns
/// the WebSocket on one thread and uses this timeout as its I/O poll cadence
/// so outbound frames are scheduled promptly without busy-spinning.
pub(crate) const WS_READ_TIMEOUT: Duration = Duration::from_millis(10);

fn resolve_ws_target_with(
    dc: u8,
    resolved_addr: Option<SocketAddr>,
    mut resolve_socket_addrs: impl FnMut(&str) -> io::Result<SocketAddr>,
) -> io::Result<(String, SocketAddr)> {
    let host = format!("kws{dc}.web.telegram.org");
    let target = match resolved_addr {
        Some(target) => target,
        None => resolve_socket_addrs(&format!("{host}:443"))?,
    };
    Ok((host, target))
}

fn resolve_ws_target(dc: u8, resolved_addr: Option<SocketAddr>) -> io::Result<(String, SocketAddr)> {
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

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Establishes a TLS connection to `kws{dc}.web.telegram.org:443`, performs the
/// WebSocket handshake with `Sec-WebSocket-Protocol: binary`, and returns the
/// connected WebSocket.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
pub fn open_ws_tunnel(dc: u8, resolved_addr: Option<SocketAddr>, protect_path: Option<&str>) -> io::Result<WsStream> {
    let url = ws_url(dc);
    let (_host, target) = resolve_ws_target(dc, resolved_addr)?;
    let tcp = connect_tcp_socket(target, protect_path)?;
    let request = build_ws_request(url.as_str())?;

    // Perform WS handshake; tungstenite handles TLS via rustls for wss:// URLs
    let (ws, _response) = tungstenite::client_tls(request, tcp)
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

        let (_host, resolved) = resolve_ws_target_with(2, Some(target), |_| {
            resolver_called.set(true);
            Ok(target)
        })
        .expect("resolve target");

        assert_eq!(resolved, target);
        assert!(!resolver_called.get());
    }
}
