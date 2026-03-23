use std::io;
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tungstenite::client::IntoClientRequest;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::WebSocket;

use crate::dc::ws_url;
use crate::protect;

/// A connected WebSocket tunnel to a Telegram DC.
pub type WsStream = WebSocket<MaybeTlsStream<TcpStream>>;

/// Read timeout on the WebSocket's underlying TCP socket. Prevents the downlink
/// reader from holding the shared mutex indefinitely during `ws.read()`, giving
/// the uplink writer a chance to send outbound frames.
const WS_READ_TIMEOUT: Duration = Duration::from_millis(100);

/// Open a WebSocket tunnel to the given Telegram DC.
///
/// Establishes a TLS connection to `kws{dc}.web.telegram.org:443`, performs the
/// WebSocket handshake with `Sec-WebSocket-Protocol: binary`, and returns the
/// connected WebSocket.
///
/// If `protect_path` is provided, the underlying TCP socket is protected from
/// Android VPN routing loops before connecting.
pub fn open_ws_tunnel(dc: u8, protect_path: Option<&str>) -> io::Result<WsStream> {
    let url = ws_url(dc);
    let host = format!("kws{dc}.web.telegram.org");

    // Resolve the endpoint and protect the raw socket before connecting so
    // Android VPN mode does not route the tunnel through itself.
    let addr = format!("{host}:443");
    let target = addr.to_socket_addrs()?.next().ok_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, format!("WS tunnel resolved no address: {addr}"))
    })?;
    let domain = match target {
        std::net::SocketAddr::V4(_) => Domain::IPV4,
        std::net::SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;

    if let Some(path) = protect_path {
        protect::protect_socket(&socket, path)?;
    }
    socket
        .connect(&SockAddr::from(target))
        .map_err(|e| io::Error::new(e.kind(), format!("WS tunnel TCP connect to {addr}: {e}")))?;
    let tcp: TcpStream = socket.into();
    tcp.set_nodelay(true)?;

    // Set a short read timeout so the relay's shared mutex is not held
    // indefinitely during ws.read(). This ensures fair bidirectional throughput.
    tcp.set_read_timeout(Some(WS_READ_TIMEOUT))?;

    // Build WS request with binary subprotocol
    let mut request = url.as_str().into_client_request().map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
    request
        .headers_mut()
        .insert("Sec-WebSocket-Protocol", tungstenite::http::HeaderValue::from_static("binary"));

    // Perform WS handshake; tungstenite handles TLS via rustls for wss:// URLs
    let (ws, _response) = tungstenite::client_tls(request, tcp)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("WS handshake: {e}")))?;

    Ok(ws)
}
