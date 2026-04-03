mod connect;
pub mod dc;
mod mtproto;
mod protect;
mod relay;

use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

pub use dc::{dc_from_ip, is_telegram_ip, ws_host, ws_url, TelegramDc, TelegramDcClass};
pub use mtproto::{classify_mtproto_seed, decrypt_init_packet, extract_dc_from_init, MtprotoSeedClassification};

/// Configuration for a WebSocket tunnel connection.
pub struct WsTunnelConfig {
    /// Unix socket path for Android VPN socket protection. `None` when not
    /// running in VPN mode.
    pub protect_path: Option<String>,
    /// Optional pre-resolved Telegram WS endpoint, usually supplied by the
    /// runtime through encrypted DNS.
    pub resolved_addr: Option<SocketAddr>,
    /// Optional TCP connect timeout for the WS bootstrap path.
    pub connect_timeout: Option<Duration>,
    /// Optional cover domain for the TLS SNI field. When set, the TLS
    /// ClientHello will use this domain instead of the real
    /// `kws{dc}.web.telegram.org`, disguising the connection as traffic to a
    /// whitelisted service (e.g. `yandex.ru`). Certificate validation is
    /// disabled when fake SNI is active.
    pub fake_sni: Option<String>,
}

/// Result of classifying a target IP for WS tunnel eligibility.
pub enum WsTunnelDecision {
    /// Target is a Telegram DC; use WS tunnel for this production DC.
    Tunnel(TelegramDc),
    /// Target is not a Telegram IP; use the normal transport path.
    Passthrough,
}

/// Classify whether a target IP should be tunneled through WebSocket.
///
/// Returns `Tunnel(dc)` for known Telegram DC IPs, `Passthrough` otherwise.
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    match ip {
        IpAddr::V4(v4) => match dc::dc_from_ip(v4) {
            Some(dc) => WsTunnelDecision::Tunnel(dc),
            None => WsTunnelDecision::Passthrough,
        },
        IpAddr::V6(_) => WsTunnelDecision::Passthrough,
    }
}

/// Execute a WebSocket tunnel relay for a Telegram connection.
///
/// `seed_request` must start with a validated 64-byte MTProto obfuscated2 init.
/// Any bytes after the first 64 are forwarded as the next WebSocket frames
/// before the relay begins draining the client socket.
pub fn relay_ws_tunnel(
    client: TcpStream,
    dc: TelegramDc,
    seed_request: Vec<u8>,
    config: &WsTunnelConfig,
) -> io::Result<()> {
    relay_ws_tunnel_with(client, dc, seed_request, config, connect::open_ws_tunnel_with_timeout, relay::ws_relay)
}

fn relay_ws_tunnel_with<OpenWs, RelayWs, Ws>(
    client: TcpStream,
    dc: TelegramDc,
    seed_request: Vec<u8>,
    config: &WsTunnelConfig,
    open_ws: OpenWs,
    relay_ws: RelayWs,
) -> io::Result<()>
where
    OpenWs: FnOnce(TelegramDc, Option<SocketAddr>, Option<&str>, Option<Duration>, Option<&str>) -> io::Result<Ws>,
    RelayWs: FnOnce(TcpStream, Ws, &[u8]) -> io::Result<()>,
{
    if seed_request.len() < 64 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            format!("WS tunnel seed request must contain 64-byte init, got {}", seed_request.len()),
        ));
    }

    let ws = open_ws(
        dc,
        config.resolved_addr,
        config.protect_path.as_deref(),
        config.connect_timeout,
        config.fake_sni.as_deref(),
    )?;
    relay_ws(client, ws, &seed_request)
}

/// Probe whether the WebSocket tunnel endpoint for a given DC is reachable.
///
/// Performs a TLS + WebSocket handshake to `wss://kws{dc}.web.telegram.org/apiws`
/// without sending any MTProto data. Returns `Ok(())` if the endpoint accepts
/// the WSS connection, or an error describing why it failed.
///
/// Intended for diagnostics/monitoring, not for relaying traffic.
pub fn probe_ws_tunnel(dc: TelegramDc) -> io::Result<()> {
    probe_ws_tunnel_with_addr(dc, None)
}

/// Probe the WebSocket tunnel endpoint for a given DC using an optional
/// pre-resolved address.
pub fn probe_ws_tunnel_with_addr(dc: TelegramDc, resolved_addr: Option<SocketAddr>) -> io::Result<()> {
    let _ws = connect::open_ws_tunnel(dc, resolved_addr, None)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{Ipv4Addr, TcpListener};

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
    fn classify_target_tunnels_known_telegram_ips() {
        let cases = [
            ("149.154.160.1", 1),
            ("149.154.165.10", 2),
            ("149.154.170.5", 3),
            ("91.108.56.100", 5),
            ("91.108.13.1", 4),
        ];
        for (ip_str, expected_dc) in cases {
            let ip: IpAddr = ip_str.parse().expect("parse ip");
            match classify_target(ip) {
                WsTunnelDecision::Tunnel(dc) => {
                    assert_eq!(dc, TelegramDc::production(expected_dc), "wrong DC for {ip_str}");
                }
                WsTunnelDecision::Passthrough => panic!("expected Tunnel for {ip_str}"),
            }
        }
    }

    #[test]
    fn classify_target_passes_through_non_telegram_ips() {
        let ip: IpAddr = "8.8.8.8".parse().expect("parse ip");
        assert!(matches!(classify_target(ip), WsTunnelDecision::Passthrough));
    }

    #[test]
    fn relay_ws_tunnel_rejects_short_seed_request() {
        let (_app, relay_client) = tcp_pair();

        let error = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(3),
            vec![0x42; 63],
            &WsTunnelConfig {
                protect_path: Some("/tmp/protect.sock".to_string()),
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni| Ok(()),
            |_client, _ws: (), _seed_request| Ok(()),
        )
        .expect_err("short seed should fail");

        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
    }

    #[test]
    fn relay_ws_tunnel_uses_injected_addr_when_opening_ws() {
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x24; 64];
        let injected_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 443);

        relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(2),
            seed_request.clone(),
            &WsTunnelConfig {
                protect_path: Some("/tmp/protect.sock".to_string()),
                resolved_addr: Some(injected_addr),
                connect_timeout: Some(Duration::from_millis(321)),
                fake_sni: None,
            },
            |dc, resolved_addr, protect_path, connect_timeout, _fake_sni| {
                assert_eq!(dc, TelegramDc::production(2));
                assert_eq!(resolved_addr, Some(injected_addr));
                assert_eq!(protect_path, Some("/tmp/protect.sock"));
                assert_eq!(connect_timeout, Some(Duration::from_millis(321)));
                Ok(())
            },
            |_client, _ws, forwarded_seed| {
                assert_eq!(forwarded_seed, seed_request.as_slice());
                Ok(())
            },
        )
        .expect("relay succeeds");
    }

    #[test]
    fn relay_ws_tunnel_surfaces_open_failures() {
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x11; 64];

        let error = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(1),
            seed_request,
            &WsTunnelConfig { protect_path: None, resolved_addr: None, connect_timeout: None, fake_sni: None },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni| {
                Err(io::Error::new(io::ErrorKind::ConnectionRefused, "boom"))
            },
            |_client, _ws: (), _seed_request| Ok(()),
        )
        .expect_err("open failure should surface");

        assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    }

    #[test]
    fn relay_ws_tunnel_surfaces_relay_failures() {
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x33; 64];

        let error = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(5),
            seed_request.clone(),
            &WsTunnelConfig { protect_path: None, resolved_addr: None, connect_timeout: None, fake_sni: None },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni| Ok(()),
            |_client, _ws: (), forwarded_seed| {
                assert_eq!(forwarded_seed, seed_request.as_slice());
                Err(io::Error::new(io::ErrorKind::BrokenPipe, "relay boom"))
            },
        )
        .expect_err("relay failure should surface");

        assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
    }
}
