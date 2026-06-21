use std::io;
use std::net::SocketAddr;

use tokio::net::UdpSocket;
use tokio::sync::Mutex;

use ripdpi_wireguard_ws::{
    CarrierSocketProtector, WsCarrier, WsCarrierError, WssCarrierStream, WssEndpoint, connect_wss_carrier,
};

#[cfg(test)]
type PlainTestWsStream = tokio_tungstenite::WebSocketStream<tokio::net::TcpStream>;

/// The transport a [`WireGuardTunnel`](super::WireGuardTunnel) runs its WireGuard
/// datagrams over.
///
/// Today's behaviour is the plain-UDP path ([`WgCarrier::Udp`]); a profile that
/// selects the WG-over-WebSocket carrier gets [`WgCarrier::Ws`], which frames
/// each WireGuard datagram as a binary WebSocket message over a single
/// protected TLS/TCP stream to port 443 (see `ripdpi-wireguard-ws`). To a DPI
/// box the flow then looks like an ordinary long-lived WebSocket instead of a
/// fixed-shape WireGuard UDP fingerprint.
///
/// The enum exposes exactly the `send_to`/`recv` surface the tunnel's datagram
/// loop already uses against a bare [`UdpSocket`], so the carrier select is a
/// drop-in replacement for the socket field. The `addr` argument to
/// [`Self::send_to`] is honoured on the UDP arm and ignored on the WS arm (the
/// carrier is already pointed at the single negotiated endpoint).
///
/// The [`WsCarrier`] needs `&mut self` to drive its sink/stream; the tunnel
/// holds the carrier behind `&self`, so the WS arm wraps it in a
/// [`tokio::sync::Mutex`]. WireGuard's datagram loop is low-frequency relative
/// to a TLS-framed stream, so the contention cost is negligible, and the mutex
/// is async (never held across a blocking call).
pub(crate) enum WgCarrier {
    /// The plain WireGuard-over-UDP transport (today's default).
    Udp(UdpSocket),
    /// The WireGuard-over-WebSocket carrier: each datagram framed as a binary
    /// WS message over one protected TLS/TCP stream.
    ///
    /// `Box`-ed to keep the enum small: the `WebSocketStream` buffers are large
    /// (~360 B) relative to the `UdpSocket` variant, and there is exactly one
    /// carrier per tunnel, so the one-time heap indirection is free of any
    /// per-packet cost (`clippy::large_enum_variant`).
    Ws(Box<Mutex<WsCarrier<WssCarrierStream>>>),
    #[cfg(test)]
    PlainTestWs(Box<Mutex<WsCarrier<PlainTestWsStream>>>),
}

impl WgCarrier {
    /// Send one WireGuard datagram over the carrier.
    ///
    /// On the UDP arm this is `UdpSocket::send_to(buf, addr)`. On the WS arm the
    /// datagram is framed as a binary WebSocket message and `addr` is ignored —
    /// the carrier targets the single endpoint negotiated at connect time. A WS
    /// transport error is surfaced as an [`io::Error`] so the tunnel's existing
    /// `let _ = self.carrier.send_to(...)` call sites stay unchanged.
    ///
    // cancel-safe: the UDP arm is a single `send_to`; the WS arm delegates to
    // `WsCarrier::send_datagram`, which is cancel-safe (a dropped send leaves at
    // most a partially-buffered frame the peer never sees as a truncated
    // datagram). The async mutex guard is released on drop.
    pub(crate) async fn send_to(&self, buf: &[u8], addr: SocketAddr) -> io::Result<usize> {
        match self {
            Self::Udp(socket) => socket.send_to(buf, addr).await,
            Self::Ws(carrier) => {
                carrier.lock().await.send_datagram(buf).await.map_err(ws_error_to_io)?;
                Ok(buf.len())
            }
            #[cfg(test)]
            Self::PlainTestWs(carrier) => {
                carrier.lock().await.send_datagram(buf).await.map_err(ws_error_to_io)?;
                Ok(buf.len())
            }
        }
    }

    /// Receive the next WireGuard datagram from the carrier into `buf`.
    ///
    /// On the UDP arm this is `UdpSocket::recv(buf)`. On the WS arm the next
    /// binary frame is unframed and copied into `buf`; a frame longer than `buf`
    /// is rejected with [`io::ErrorKind::InvalidData`] rather than silently
    /// truncating a WireGuard packet (the caller's `recv_buf` is `MAX_PACKET`,
    /// which bounds any valid WG datagram).
    ///
    // cancel-safe: the UDP arm is a single `recv`; the WS arm delegates to
    // `WsCarrier::recv_datagram`, which is cancel-safe (a dropped read resumes
    // at the next frame boundary). The async mutex guard is released on drop.
    pub(crate) async fn recv(&self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Udp(socket) => socket.recv(buf).await,
            Self::Ws(carrier) => {
                let datagram = carrier.lock().await.recv_datagram().await.map_err(ws_error_to_io)?;
                if datagram.len() > buf.len() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "carrier datagram larger than the tunnel receive buffer",
                    ));
                }
                buf[..datagram.len()].copy_from_slice(&datagram);
                Ok(datagram.len())
            }
            #[cfg(test)]
            Self::PlainTestWs(carrier) => {
                let datagram = carrier.lock().await.recv_datagram().await.map_err(ws_error_to_io)?;
                if datagram.len() > buf.len() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "carrier datagram larger than the tunnel receive buffer",
                    ));
                }
                buf[..datagram.len()].copy_from_slice(&datagram);
                Ok(datagram.len())
            }
        }
    }
}

/// Open a protected WG-over-WebSocket carrier to `request_url`.
///
/// The sequence mirrors the UDP `bind_tunnel_socket` fail-closed posture, but for
/// a TCP carrier socket:
///
/// 1. [`connect_protected_carrier`] creates the outbound `TcpStream` and runs
///    the injected `protector` on its fd *before* `connect()` returns — the
///    `VpnService.protect` invariant. On protect failure the socket is dropped
///    and the error propagates; no unprotected socket survives.
/// 2. The parsed `wss://` endpoint owns the TCP target, Host header, request
///    path, and TLS SNI. TLS and the WebSocket client handshake are layered over
///    the protected stream, so the production carrier behaves like a real WSS
///    endpoint. The WSS->UDP terminator remains out of this repo's scope — see
///    the crate docs.
/// 3. The upgraded stream is wrapped in a [`WsCarrier`] ready to frame WireGuard
///    datagrams.
///
// cancel-safe: a dropped future drops the in-flight connect/handshake, closing
// the carrier socket. No caller-visible state is published before the `Ok`.
pub(crate) async fn connect_ws_carrier<P>(request_url: &str, protector: &P) -> io::Result<WgCarrier>
where
    P: CarrierSocketProtector + ?Sized,
{
    let wss_endpoint = WssEndpoint::parse(request_url).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid carrier WSS endpoint: {error}"))
    })?;
    let carrier = connect_wss_carrier(&wss_endpoint, protector).await?;
    Ok(WgCarrier::Ws(Box::new(Mutex::new(carrier))))
}

fn ws_error_to_io(error: WsCarrierError) -> io::Error {
    match error {
        WsCarrierError::Closed => io::Error::new(io::ErrorKind::UnexpectedEof, "carrier websocket closed by peer"),
        WsCarrierError::UnexpectedFrameType => {
            io::Error::new(io::ErrorKind::InvalidData, "carrier websocket sent a non-binary frame")
        }
        WsCarrierError::Transport(inner) => io::Error::other(format!("carrier ws transport: {inner}")),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;

    use boringtun::noise::{Tunn, TunnResult};
    use boringtun::x25519::{PublicKey, StaticSecret};
    use ripdpi_wireguard_ws::connect_protected_carrier;
    use tokio::net::{TcpListener, UdpSocket};
    use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
    use tokio_tungstenite::tungstenite::http::HeaderValue;
    use tokio_tungstenite::{accept_hdr_async, client_async};

    use super::*;

    const MAX_WG: usize = 2048;
    // Loopback (127.0.0.1) is protect-exempt in production, but the carrier seam
    // still invokes the injected protector unconditionally so the fail-closed
    // path is exercised — this fake accepts, recording that it ran.
    fn loopback_protector(ran: Arc<AtomicBool>) -> impl Fn(std::os::fd::RawFd) -> io::Result<()> + Send + Sync {
        move |_fd| {
            ran.store(true, Ordering::SeqCst);
            Ok(())
        }
    }

    #[allow(clippy::result_large_err)]
    fn accept_binary_subprotocol(_request: &Request, mut response: Response) -> Result<Response, ErrorResponse> {
        response.headers_mut().insert("Sec-WebSocket-Protocol", HeaderValue::from_static("binary"));
        Ok(response)
    }

    /// A LOCAL in-process WS relay terminating WS frames on `127.0.0.1` and
    /// forwarding the unframed WireGuard datagrams to a local UDP responder
    /// socket (and the responder's replies back over the WS carrier). This is
    /// the in-repo stand-in for the out-of-scope production WSS->UDP terminator
    /// (NO BACKEND SERVER rule): it proves the CLIENT carrier path drives a real
    /// boringtun handshake end-to-end, no internet, no external server.
    async fn spawn_ws_to_udp_relay(ws_listener: TcpListener, responder_udp_addr: std::net::SocketAddr) {
        tokio::spawn(async move {
            let (tcp, _peer) = ws_listener.accept().await.expect("relay accept tcp");
            let ws = accept_hdr_async(tcp, accept_binary_subprotocol).await.expect("relay ws handshake");
            let mut server = WsCarrier::new(ws);

            // A dedicated UDP socket the relay uses to talk to the responder.
            let relay_udp = UdpSocket::bind("127.0.0.1:0").await.expect("relay udp bind");
            relay_udp.connect(responder_udp_addr).await.expect("relay udp connect");

            loop {
                let mut udp_buf = [0u8; MAX_WG];
                tokio::select! {
                    // WS -> UDP: unframe a carrier datagram, forward to responder.
                    framed = server.recv_datagram() => {
                        match framed {
                            Ok(datagram) => {
                                let _ = relay_udp.send(&datagram).await;
                            }
                            Err(_) => break,
                        }
                    }
                    // UDP -> WS: responder reply, frame back over the carrier.
                    received = relay_udp.recv(&mut udp_buf) => {
                        match received {
                            Ok(size) => {
                                if server.send_datagram(&udp_buf[..size]).await.is_err() {
                                    break;
                                }
                            }
                            Err(_) => break,
                        }
                    }
                }
            }
        });
    }

    /// A real WireGuard responder peer over a plain UDP socket. Mirrors the
    /// production exit's data plane (the WS terminator's UDP side) using
    /// boringtun directly: decapsulate initiations, emit the handshake response.
    async fn spawn_udp_responder(responder_udp: UdpSocket, responder: Box<Tunn>, completed: Arc<AtomicBool>) {
        tokio::spawn(async move {
            let mut peer = responder;
            let mut recv_buf = [0u8; MAX_WG];
            loop {
                let Ok((size, from)) = responder_udp.recv_from(&mut recv_buf).await else {
                    break;
                };
                let mut send_buf = [0u8; MAX_WG];
                let result = peer.decapsulate(None, &recv_buf[..size], &mut send_buf);
                match result {
                    TunnResult::WriteToNetwork(packet) => {
                        // Handshake response (and any queued follow-ups).
                        let _ = responder_udp.send_to(packet, from).await;
                        // Drain any additional queued packets boringtun wants to send.
                        loop {
                            let mut more = [0u8; MAX_WG];
                            match peer.decapsulate(None, &[], &mut more) {
                                TunnResult::WriteToNetwork(p) => {
                                    let _ = responder_udp.send_to(p, from).await;
                                }
                                _ => break,
                            }
                        }
                    }
                    TunnResult::WriteToTunnelV4(_, _) | TunnResult::WriteToTunnelV6(_, _) => {
                        // A decrypted data packet means the session is live: the
                        // handshake completed through the carrier.
                        completed.store(true, Ordering::SeqCst);
                    }
                    _ => {}
                }
            }
        });
    }

    /// End-to-end: an initiator drives a REAL boringtun Noise handshake whose
    /// datagrams ride the WG-over-WebSocket carrier (over a protected loopback
    /// TcpStream) to a local WS relay, which forwards them to a local UDP
    /// boringtun responder. The handshake completing — and a data packet
    /// surviving the round trip — proves the carrier transport is wire-correct.
    #[tokio::test]
    async fn wg_handshake_completes_through_ws_carrier() {
        // Deterministic static keypairs for the two peers (the in-crate test
        // pattern used by `amnezia.rs`: `StaticSecret::from([u8; 32])`, no RNG
        // dependency). Distinct seeds give distinct, valid Curve25519 secrets.
        let init_secret = StaticSecret::from([7u8; 32]);
        let resp_secret = StaticSecret::from([9u8; 32]);
        let init_public = PublicKey::from(&init_secret);
        let resp_public = PublicKey::from(&resp_secret);

        // Responder UDP socket + boringtun peer (the WS terminator's UDP side).
        let responder_udp = UdpSocket::bind("127.0.0.1:0").await.expect("responder udp bind");
        let responder_addr = responder_udp.local_addr().expect("responder addr");
        let responder = Box::new(Tunn::new(resp_secret, init_public, None, None, 1, None));
        let completed = Arc::new(AtomicBool::new(false));
        spawn_udp_responder(responder_udp, responder, Arc::clone(&completed)).await;

        // WS relay: terminates the carrier and forwards to the responder UDP.
        let ws_listener = TcpListener::bind("127.0.0.1:0").await.expect("ws relay bind");
        let ws_addr = ws_listener.local_addr().expect("ws addr");
        spawn_ws_to_udp_relay(ws_listener, responder_addr).await;

        // Client carrier: protected connect (loopback fake protector) + WS upgrade.
        let protect_ran = Arc::new(AtomicBool::new(false));
        let protector = loopback_protector(Arc::clone(&protect_ran));
        let endpoint = WssEndpoint::parse(&format!("wss://{ws_addr}/wg")).expect("loopback WSS endpoint");
        let stream = connect_protected_carrier(ws_addr, &protector).await.expect("protected loopback connect");
        let request = endpoint.build_client_request().expect("loopback endpoint request");
        let (ws, _response) = client_async(request, stream).await.expect("plain loopback ws upgrade");
        let carrier = WgCarrier::PlainTestWs(Box::new(Mutex::new(WsCarrier::new(ws))));
        assert!(protect_ran.load(Ordering::SeqCst), "carrier socket was protect-ed before connect");

        // Initiator boringtun peer: emit the handshake initiation over the carrier.
        let mut initiator = Tunn::new(init_secret, resp_public, None, None, 2, None);
        let mut init_buf = [0u8; MAX_WG];
        let TunnResult::WriteToNetwork(initiation) = initiator.format_handshake_initiation(&mut init_buf, false) else {
            panic!("initiator must produce a handshake initiation");
        };
        carrier.send_to(initiation, responder_addr).await.expect("send initiation over carrier");

        // Read the handshake response back over the carrier and feed boringtun
        // until the session is established (encapsulating a data packet succeeds).
        let mut established = false;
        for _ in 0..8 {
            let mut recv_buf = [0u8; MAX_WG];
            let Ok(Ok(size)) = tokio::time::timeout(Duration::from_secs(2), carrier.recv(&mut recv_buf)).await else {
                break;
            };
            let mut decap_buf = [0u8; MAX_WG];
            let _ = initiator.decapsulate(None, &recv_buf[..size], &mut decap_buf);

            // After the response, try to encapsulate a real data packet; success
            // (WriteToNetwork) means the Noise handshake completed. The payload is
            // a minimal IPv4 packet (version nibble 4) so the responder, on
            // decrypt, classifies it as `WriteToTunnelV4` rather than a malformed
            // inner packet — proving a live transport session through the carrier.
            let mut payload = [0u8; 20];
            payload[0] = 0x45; // IPv4, IHL=5 (20-byte header)
            payload[3] = 20; // total length low byte
            let mut data_buf = [0u8; MAX_WG];
            if let TunnResult::WriteToNetwork(packet) = initiator.encapsulate(&payload, &mut data_buf) {
                carrier.send_to(packet, responder_addr).await.expect("send data over carrier");
                established = true;
                break;
            }
        }

        assert!(established, "the WireGuard handshake must complete through the WS carrier");

        // Give the responder a moment to decrypt the data packet forwarded by the
        // relay, confirming the carrier round-trips a live transport session.
        for _ in 0..20 {
            if completed.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        assert!(completed.load(Ordering::SeqCst), "a data packet must decrypt at the responder through the carrier");
    }
}
