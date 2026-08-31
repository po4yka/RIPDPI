#![forbid(unsafe_code)]

mod connect;
pub mod httpupgrade;
mod mtproto;
mod protect;
mod relay;
pub mod transport;

use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

pub use httpupgrade::{
    HttpUpgradeConfig, HttpUpgradeError, HttpUpgradeTransport, UpgradeResponse, build_upgrade_request,
    parse_upgrade_response,
};
pub use mtproto::{
    MtprotoTransportFamily, classify_mtproto_seed, decrypt_init_packet, extract_dc_from_init, redact_seed,
};
pub use ripdpi_ws_transport_port::{
    CloudflareWorkerRoute, MtprotoSeedClassification, TelegramDc, TelegramDcClass, WorkerBearer, WsTunnelConfig,
    WsTunnelDecision, classify_target, dc_from_ip, dc_from_ipv6, is_telegram_ip, ws_host, ws_url,
};
pub use transport::{EarlyData, WsTransport, WsTransportConfig, WsTransportError, build_ws_request};

#[derive(Debug, Default, Clone, Copy)]
pub struct TelegramWsTransport;

impl ripdpi_ws_transport_port::WsTransport for TelegramWsTransport {
    fn classify_mtproto_seed(&self, seed: &[u8]) -> MtprotoSeedClassification {
        classify_mtproto_seed(seed)
    }

    fn relay(
        &self,
        client: TcpStream,
        dc: TelegramDc,
        seed_request: Vec<u8>,
        config: &WsTunnelConfig,
    ) -> io::Result<()> {
        relay_ws_tunnel(client, dc, seed_request, config)
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
    OpenWs: FnOnce(
        TelegramDc,
        Option<SocketAddr>,
        Option<&str>,
        Option<Duration>,
        Option<&str>,
        Option<&CloudflareWorkerRoute>,
    ) -> io::Result<Ws>,
    RelayWs: FnOnce(TcpStream, Ws, &[u8]) -> io::Result<()>,
{
    if seed_request.len() < 64 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            format!("WS tunnel seed request must contain 64-byte init, got {}", seed_request.len()),
        ));
    }

    // fake-SNI mode disables standard TLS cert verification (see connect.rs).
    // Refuse the connection when the operator has not explicitly acknowledged
    // the bypass via allow_insecure_sni, so a misconfigured profile cannot
    // silently route traffic through a cover-cert path.
    let effective_fake_sni = if config.allow_insecure_sni { config.fake_sni.as_deref() } else { None };
    if config.fake_sni.is_some() && !config.allow_insecure_sni {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "WS tunnel fake_sni requires allow_insecure_sni=true (TLS cert verification would be disabled)",
        ));
    }
    if config.worker_route.is_some() && config.fake_sni.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Cloudflare Worker WS tunnel route requires verified Worker TLS and cannot be combined with fake_sni",
        ));
    }

    let ws = open_ws(
        dc,
        config.resolved_addr,
        config.protect_path.as_deref(),
        config.connect_timeout,
        effective_fake_sni,
        config.worker_route.as_ref(),
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

    use std::net::{IpAddr, Ipv4Addr, TcpListener};

    fn tcp_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect tcp pair");
        let (server, _) = listener.accept().expect("accept tcp pair");
        (client, server)
    }

    #[test]
    fn classify_target_returns_passthrough_for_non_telegram_ipv6() {
        // Localhost and a non-Telegram public IPv6 must passthrough.
        let cases = [
            "::1",
            "2001:4860:4860::8888", // Google Public DNS
            "2606:4700:4700::1111", // Cloudflare DNS
        ];
        for s in cases {
            let ip: IpAddr = s.parse().expect("parse v6");
            assert!(matches!(classify_target(ip), WsTunnelDecision::Passthrough), "expected Passthrough for {s}");
        }
    }

    #[test]
    fn classify_target_tunnels_known_telegram_ipv6_supernets() {
        // 2001:67c:4e8::/48 -> Amsterdam (DC2)
        let amsterdam: IpAddr = "2001:67c:4e8:0:1::1".parse().expect("parse v6");
        match classify_target(amsterdam) {
            WsTunnelDecision::Tunnel(dc) => assert_eq!(dc, TelegramDc::production(2)),
            WsTunnelDecision::Passthrough => panic!("expected Tunnel for Amsterdam v6"),
        }

        // 2001:b28:f23c..f23f -> Miami / Singapore (DC3 representative)
        for prefix in ["2001:b28:f23c::1", "2001:b28:f23d::abc", "2001:b28:f23f::dead:beef"] {
            let ip: IpAddr = prefix.parse().expect("parse v6");
            match classify_target(ip) {
                WsTunnelDecision::Tunnel(dc) => assert_eq!(dc, TelegramDc::production(3), "wrong DC for {prefix}"),
                WsTunnelDecision::Passthrough => panic!("expected Tunnel for {prefix}"),
            }
        }
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
                allow_insecure_sni: false,
                worker_route: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni, _worker_route| Ok(()),
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
                allow_insecure_sni: false,
                worker_route: None,
            },
            |dc, resolved_addr, protect_path, connect_timeout, _fake_sni, worker_route| {
                assert_eq!(dc, TelegramDc::production(2));
                assert_eq!(resolved_addr, Some(injected_addr));
                assert_eq!(protect_path, Some("/tmp/protect.sock"));
                assert_eq!(connect_timeout, Some(Duration::from_millis(321)));
                assert_eq!(worker_route, None);
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
            &WsTunnelConfig {
                protect_path: None,
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: None,
                allow_insecure_sni: false,
                worker_route: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni, _worker_route| {
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
            &WsTunnelConfig {
                protect_path: None,
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: None,
                allow_insecure_sni: false,
                worker_route: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni, _worker_route| Ok(()),
            |_client, _ws: (), forwarded_seed| {
                assert_eq!(forwarded_seed, seed_request.as_slice());
                Err(io::Error::new(io::ErrorKind::BrokenPipe, "relay boom"))
            },
        )
        .expect_err("relay failure should surface");

        assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
    }

    #[test]
    fn relay_ws_tunnel_refuses_fake_sni_without_allow_insecure_acknowledgement() {
        // Operator must opt into the TLS cert-bypass via allow_insecure_sni
        // before fake_sni is honoured. Without the flag, the relay refuses
        // to start and surfaces a PermissionDenied error so misconfiguration
        // is loud.
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x55; 64];

        let error = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(2),
            seed_request,
            &WsTunnelConfig {
                protect_path: None,
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: Some("yandex.ru".to_string()),
                allow_insecure_sni: false,
                worker_route: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni, _worker_route| Ok(()),
            |_client, _ws: (), _seed_request| Ok(()),
        )
        .expect_err("fake_sni without allow_insecure_sni must refuse");

        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        assert!(
            error.to_string().contains("allow_insecure_sni"),
            "error message should name the required flag: {error}",
        );
    }

    #[test]
    fn relay_ws_tunnel_honours_fake_sni_when_allow_insecure_sni_is_set() {
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x66; 64];

        let result = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(1),
            seed_request,
            &WsTunnelConfig {
                protect_path: None,
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: Some("yandex.ru".to_string()),
                allow_insecure_sni: true,
                worker_route: None,
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, fake_sni, worker_route| {
                assert_eq!(fake_sni, Some("yandex.ru"));
                assert_eq!(worker_route, None);
                Ok(())
            },
            |_client, _ws: (), _seed_request| Ok(()),
        );

        assert!(result.is_ok());
    }

    #[test]
    fn relay_ws_tunnel_refuses_worker_route_with_fake_sni() {
        let (_app, relay_client) = tcp_pair();
        let seed_request = vec![0x77; 64];
        let worker_route = CloudflareWorkerRoute::parse("https://edge.example.workers.dev/relay", "secret-token")
            .expect("valid worker route");

        let error = relay_ws_tunnel_with(
            relay_client,
            TelegramDc::production(2),
            seed_request,
            &WsTunnelConfig {
                protect_path: None,
                resolved_addr: None,
                connect_timeout: None,
                fake_sni: Some("cover.example".to_string()),
                allow_insecure_sni: true,
                worker_route: Some(worker_route),
            },
            |_dc, _resolved_addr, _protect_path, _connect_timeout, _fake_sni, _worker_route| {
                panic!("unsafe Worker/fake-SNI config must fail before opening WS")
            },
            |_client, _ws: (), _seed_request| Ok(()),
        )
        .expect_err("Worker route plus fake-SNI must fail");

        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        assert!(
            error.to_string().contains("cannot be combined with fake_sni"),
            "error should name the conflicting option: {error}",
        );
    }
}
