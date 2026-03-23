mod connect;
pub mod dc;
mod protect;
mod relay;

use std::io::{self, Read};
use std::net::{IpAddr, SocketAddr, TcpStream};

pub use dc::{dc_from_ip, extract_dc_from_init, is_telegram_ip};

/// Configuration for a WebSocket tunnel connection.
pub struct WsTunnelConfig {
    /// Unix socket path for Android VPN socket protection. `None` when not
    /// running in VPN mode.
    pub protect_path: Option<String>,
    /// Optional pre-resolved Telegram WS endpoint, usually supplied by the
    /// runtime through encrypted DNS.
    pub resolved_addr: Option<SocketAddr>,
}

/// Result of classifying a target IP for WS tunnel eligibility.
pub enum WsTunnelDecision {
    /// Target is a Telegram DC; use WS tunnel with this DC number (1-5).
    Tunnel(u8),
    /// Target is not a Telegram IP; use the normal transport path.
    Passthrough,
}

/// Error from a WS tunnel attempt that preserves the init packet for fallback.
#[derive(Debug)]
pub struct WsTunnelError {
    /// The IO error that caused the tunnel to fail.
    pub error: io::Error,
    /// The 64-byte init packet already read from the client, if available.
    /// Can be passed as `seed_request` to the desync fallback path.
    pub init_packet: Option<[u8; 64]>,
}

/// Classify whether a target IP should be tunneled through WebSocket.
///
/// Returns `Tunnel(dc)` for known Telegram DC IPs, `Passthrough` otherwise.
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    match ip {
        IpAddr::V4(v4) => match dc::dc_from_ip(v4) {
            Some(dc_num) => WsTunnelDecision::Tunnel(dc_num),
            None => WsTunnelDecision::Passthrough,
        },
        IpAddr::V6(_) => WsTunnelDecision::Passthrough,
    }
}

/// Execute a WebSocket tunnel relay for a Telegram connection.
///
/// Reads the first 64 bytes (MTProto obfuscated2 init packet) from `client`,
/// extracts the DC number (falling back to `target` IP-based detection),
/// opens a WSS connection to `wss://kws{dc}.web.telegram.org/apiws`, sends
/// the init packet as the first binary frame, and relays data bidirectionally
/// until one side closes.
///
/// On failure, returns a `WsTunnelError` that includes the init packet (if it
/// was successfully read) so the caller can fall back to the desync path.
pub fn relay_ws_tunnel(
    client: TcpStream,
    fallback_dc: u8,
    target: SocketAddr,
    config: &WsTunnelConfig,
) -> Result<(), WsTunnelError> {
    relay_ws_tunnel_with(client, fallback_dc, target, config, connect::open_ws_tunnel, relay::ws_relay)
}

fn relay_ws_tunnel_with<OpenWs, RelayWs, Ws>(
    mut client: TcpStream,
    fallback_dc: u8,
    target: SocketAddr,
    config: &WsTunnelConfig,
    open_ws: OpenWs,
    relay_ws: RelayWs,
) -> Result<(), WsTunnelError>
where
    OpenWs: FnOnce(u8, Option<SocketAddr>, Option<&str>) -> io::Result<Ws>,
    RelayWs: FnOnce(TcpStream, Ws, &[u8; 64]) -> io::Result<()>,
{
    // Read the 64-byte obfuscated2 init packet
    let mut init = [0u8; 64];
    if let Err(error) = client.read_exact(&mut init) {
        return Err(WsTunnelError { error, init_packet: None });
    }

    // Try to extract DC from init packet, fall back to IP-based or provided DC
    let dc = dc::extract_dc_from_init(&init).unwrap_or_else(|| {
        if let IpAddr::V4(v4) = target.ip() {
            dc::dc_from_ip(v4).unwrap_or(fallback_dc)
        } else {
            fallback_dc
        }
    });

    let resolved_addr = if dc == fallback_dc { config.resolved_addr } else { None };

    let ws = open_ws(dc, resolved_addr, config.protect_path.as_deref())
        .map_err(|error| WsTunnelError { error, init_packet: Some(init) })?;

    relay_ws(client, ws, &init).map_err(|error| WsTunnelError { error, init_packet: Some(init) })
}

/// Probe whether the WebSocket tunnel endpoint for a given DC is reachable.
///
/// Performs a TLS + WebSocket handshake to `wss://kws{dc}.web.telegram.org/apiws`
/// without sending any MTProto data. Returns `Ok(())` if the endpoint accepts
/// the WSS connection, or an error describing why it failed.
///
/// Intended for diagnostics/monitoring, not for relaying traffic.
pub fn probe_ws_tunnel(dc: u8) -> io::Result<()> {
    probe_ws_tunnel_with_addr(dc, None)
}

/// Probe the WebSocket tunnel endpoint for a given DC using an optional
/// pre-resolved address.
pub fn probe_ws_tunnel_with_addr(dc: u8, resolved_addr: Option<SocketAddr>) -> io::Result<()> {
    let _ws = connect::open_ws_tunnel(dc, resolved_addr, None)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    use aes::Aes256;
    use cipher::{KeyIvInit, StreamCipher};
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    type Aes256Ctr = ctr::Ctr128BE<Aes256>;

    fn tcp_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect tcp pair");
        let (server, _) = listener.accept().expect("accept tcp pair");
        (client, server)
    }

    #[test]
    fn classify_target_returns_passthrough_for_ipv6() {
        let decision = classify_target(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST));

        assert!(matches!(decision, WsTunnelDecision::Passthrough));
    }

    #[test]
    fn relay_ws_tunnel_preserves_init_packet_when_open_fails() {
        let (mut app, relay_client) = tcp_pair();
        let init = [0x42; 64];
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&init).expect("write init");
        });

        let error = relay_ws_tunnel_with(
            relay_client,
            3,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443),
            &WsTunnelConfig { protect_path: Some("/tmp/protect.sock".to_string()), resolved_addr: None },
            |dc, resolved_addr, protect_path| {
                assert_eq!(dc, 3);
                assert_eq!(resolved_addr, None);
                assert_eq!(protect_path, Some("/tmp/protect.sock"));
                Err(io::Error::new(io::ErrorKind::ConnectionRefused, "boom"))
            },
            |_client, _ws: (), _init| Ok(()),
        )
        .expect_err("open failure should surface");

        writer.join().expect("join writer thread");
        assert_eq!(error.error.kind(), io::ErrorKind::ConnectionRefused);
        assert_eq!(error.init_packet, Some(init));
    }

    #[test]
    fn relay_ws_tunnel_preserves_init_packet_when_relay_fails() {
        let (mut app, relay_client) = tcp_pair();
        let init = [0x24; 64];
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&init).expect("write init");
        });

        let error = relay_ws_tunnel_with(
            relay_client,
            2,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443),
            &WsTunnelConfig { protect_path: None, resolved_addr: None },
            |dc, resolved_addr, protect_path| {
                assert_eq!(dc, 2);
                assert_eq!(resolved_addr, None);
                assert_eq!(protect_path, None);
                Ok(())
            },
            |_client, _ws, forwarded_init| {
                assert_eq!(forwarded_init, &init);
                Err(io::Error::new(io::ErrorKind::BrokenPipe, "relay boom"))
            },
        )
        .expect_err("relay failure should surface");

        writer.join().expect("join writer thread");
        assert_eq!(error.error.kind(), io::ErrorKind::BrokenPipe);
        assert_eq!(error.init_packet, Some(init));
    }

    #[test]
    fn relay_ws_tunnel_reports_short_init_without_fallback_packet() {
        let (mut app, relay_client) = tcp_pair();
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&[1, 2, 3]).expect("write short init");
        });

        let error = relay_ws_tunnel_with(
            relay_client,
            1,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443),
            &WsTunnelConfig { protect_path: None, resolved_addr: None },
            |_dc, _resolved_addr, _protect_path| Ok(()),
            |_client, _ws, _init| Ok(()),
        )
        .expect_err("short init should fail");

        writer.join().expect("join writer thread");
        assert_eq!(error.error.kind(), io::ErrorKind::UnexpectedEof);
        assert_eq!(error.init_packet, None);
    }

    #[test]
    fn relay_ws_tunnel_uses_injected_addr_when_effective_dc_matches_fallback() {
        let (mut app, relay_client) = tcp_pair();
        let init = build_test_init_packet(3);
        let resolved_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 443);
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&init).expect("write init");
        });

        relay_ws_tunnel_with(
            relay_client,
            3,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443),
            &WsTunnelConfig { protect_path: None, resolved_addr: Some(resolved_addr) },
            |dc, injected_addr, protect_path| {
                assert_eq!(dc, 3);
                assert_eq!(injected_addr, Some(resolved_addr));
                assert_eq!(protect_path, None);
                Ok(())
            },
            |_client, _ws, forwarded_init| {
                assert_eq!(forwarded_init, &init);
                Ok(())
            },
        )
        .expect("relay succeeds");

        writer.join().expect("join writer thread");
    }

    #[test]
    fn relay_ws_tunnel_discards_injected_addr_when_init_dc_differs_from_fallback() {
        let (mut app, relay_client) = tcp_pair();
        let init = build_test_init_packet(4);
        let resolved_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 11)), 443);
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&init).expect("write init");
        });

        relay_ws_tunnel_with(
            relay_client,
            2,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443),
            &WsTunnelConfig { protect_path: None, resolved_addr: Some(resolved_addr) },
            |dc, injected_addr, protect_path| {
                assert_eq!(dc, 4);
                assert_eq!(injected_addr, None);
                assert_eq!(protect_path, None);
                Ok(())
            },
            |_client, _ws, forwarded_init| {
                assert_eq!(forwarded_init, &init);
                Ok(())
            },
        )
        .expect("relay succeeds");

        writer.join().expect("join writer thread");
    }

    fn build_test_init_packet(dc_id: i32) -> [u8; 64] {
        let mut plaintext = [0u8; 64];
        plaintext[60..64].copy_from_slice(&dc_id.to_le_bytes());

        let key: [u8; 32] = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        ];
        let iv: [u8; 16] =
            [0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0];

        let mut ciphertext = plaintext;
        let mut cipher = Aes256Ctr::new((&key).into(), (&iv).into());
        cipher.apply_keystream(&mut ciphertext);
        ciphertext[8..40].copy_from_slice(&key);
        ciphertext[40..56].copy_from_slice(&iv);
        ciphertext
    }
}
