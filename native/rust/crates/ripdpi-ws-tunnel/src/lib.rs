#![forbid(unsafe_code)]

mod connect;
pub mod dc;
pub mod httpupgrade;
mod mtproto;
mod protect;
mod relay;
pub mod transport;

use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

pub use dc::{TelegramDc, TelegramDcClass, dc_from_ip, is_telegram_ip, ws_host, ws_url};
pub use httpupgrade::{
    HttpUpgradeConfig, HttpUpgradeError, HttpUpgradeTransport, UpgradeResponse, build_upgrade_request,
    parse_upgrade_response,
};
pub use mtproto::{
    MtprotoSeedClassification, MtprotoTransportFamily, classify_mtproto_seed, decrypt_init_packet,
    extract_dc_from_init, redact_seed,
};
pub use transport::{EarlyData, WsTransport, WsTransportConfig, WsTransportError, build_ws_request};

#[derive(Clone, PartialEq, Eq)]
pub struct WorkerBearer(String);

impl WorkerBearer {
    pub fn parse(value: impl Into<String>) -> io::Result<Self> {
        let value = value.into();
        if value.is_empty() || value.len() > 4096 || !is_rfc6750_bearer_token(value.as_bytes()) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker bearer must be a bounded RFC 6750 bearer token",
            ));
        }
        Ok(Self(value))
    }

    pub fn expose_secret(&self) -> &str {
        &self.0
    }
}

fn is_rfc6750_bearer_token(bearer: &[u8]) -> bool {
    let padding_start = bearer.iter().position(|byte| *byte == b'=').unwrap_or(bearer.len());
    padding_start > 0
        && bearer[..padding_start].iter().all(|byte| byte.is_ascii_alphanumeric() || b"-._~+/".contains(byte))
        && bearer[padding_start..].iter().all(|byte| *byte == b'=')
}

impl std::fmt::Debug for WorkerBearer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("WorkerBearer(<redacted>)")
    }
}

/// Validated optional Cloudflare Worker route for the Telegram WS tunnel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CloudflareWorkerRoute {
    host: String,
    request_authority: String,
    port: u16,
    request_path: String,
    bearer: WorkerBearer,
}

impl CloudflareWorkerRoute {
    pub fn parse(url: impl AsRef<str>, bearer: impl Into<String>) -> io::Result<Self> {
        let url = url.as_ref();
        validate_worker_url_characters(url)?;
        let without_scheme = if let Some(rest) = url.strip_prefix("https://") {
            rest
        } else if let Some(rest) = url.strip_prefix("wss://") {
            rest
        } else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL scheme must be https or wss",
            ));
        };
        if without_scheme.contains('#') {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL must not contain a fragment",
            ));
        }
        let authority_end = without_scheme.find(['/', '?']).unwrap_or(without_scheme.len());
        let (authority, suffix) = without_scheme.split_at(authority_end);
        if authority.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker URL must contain a hostname"));
        }
        if authority.contains('@') {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker URL must not contain userinfo"));
        }
        let (host, port) = parse_worker_authority(authority)?;
        if host.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker URL must contain a hostname"));
        }
        let request_path = if suffix.is_empty() {
            "/".to_string()
        } else if suffix.starts_with('?') {
            format!("/{suffix}")
        } else {
            suffix.to_string()
        };
        Ok(Self {
            host,
            request_authority: authority.to_string(),
            port,
            request_path,
            bearer: WorkerBearer::parse(bearer)?,
        })
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn request_authority(&self) -> &str {
        &self.request_authority
    }

    pub fn request_path(&self) -> &str {
        &self.request_path
    }

    pub fn bearer(&self) -> &WorkerBearer {
        &self.bearer
    }
}

fn parse_worker_authority(authority: &str) -> io::Result<(String, u16)> {
    if let Some(stripped) = authority.strip_prefix('[') {
        let (host, rest) = stripped.split_once(']').ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host is missing closing bracket")
        })?;
        host.parse::<std::net::Ipv6Addr>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker IPv6 hostname"))?;
        let port = if rest.is_empty() {
            443
        } else {
            parse_worker_port(rest.strip_prefix(':').ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host has invalid port delimiter")
            })?)?
        };
        return Ok((host.to_string(), port));
    }
    if let Some((host, port)) = authority.rsplit_once(':') {
        if host.contains(':') {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host must use brackets"));
        }
        validate_worker_hostname(host)?;
        return Ok((host.to_string(), parse_worker_port(port)?));
    }
    if authority.contains(':') {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host must use brackets"));
    }
    validate_worker_hostname(authority)?;
    Ok((authority.to_string(), 443))
}

fn validate_worker_url_characters(url: &str) -> io::Result<()> {
    if !url.is_ascii() || url.bytes().any(|byte| byte <= b' ' || byte == 0x7f) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Cloudflare Worker URL must contain only visible ASCII characters",
        ));
    }
    let bytes = url.as_bytes();
    for (index, byte) in bytes.iter().copied().enumerate() {
        let allowed = byte.is_ascii_alphanumeric() || b"-._~:/?[]@!$&'()*+,;=%".contains(&byte);
        if !allowed {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL contains an invalid URI character",
            ));
        }
        if byte == b'%'
            && (index + 2 >= bytes.len()
                || !bytes[index + 1].is_ascii_hexdigit()
                || !bytes[index + 2].is_ascii_hexdigit())
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL contains invalid percent encoding",
            ));
        }
    }
    Ok(())
}

fn validate_worker_hostname(host: &str) -> io::Result<()> {
    if host.is_empty()
        || host.split('.').any(|label| {
            label.is_empty()
                || label.starts_with('-')
                || label.ends_with('-')
                || !label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
    {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker hostname"));
    }
    Ok(())
}

fn parse_worker_port(port: &str) -> io::Result<u16> {
    port.parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker port"))
}

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
    ///
    /// **Requires `allow_insecure_sni == true`.** A `fake_sni` value
    /// is silently ignored without an explicit opt-in, and
    /// `relay_ws_tunnel` returns a `PermissionDenied` error so the
    /// misconfiguration is loud rather than quiet.
    pub fake_sni: Option<String>,
    /// Explicit operator acknowledgement that fake-SNI mode disables
    /// standard TLS certificate verification. Required to honour
    /// `fake_sni`; defaults to `false` for safe-by-default behaviour.
    /// See
    /// completed task `gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry` (see git history).
    pub allow_insecure_sni: bool,
    /// Optional operator-owned Cloudflare Worker route. When set, the WS
    /// tunnel still carries only Telegram MTProto, but the outer TLS/WebSocket
    /// endpoint is the Worker host and the canonical Telegram gateway is sent
    /// in `X-Ripdpi-Upstream`.
    pub worker_route: Option<CloudflareWorkerRoute>,
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
/// IPv6 dispatch uses `dc::dc_from_ipv6` against Telegram's published
/// v6 supernets.
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    match ip {
        IpAddr::V4(v4) => match dc::dc_from_ip(v4) {
            Some(dc) => WsTunnelDecision::Tunnel(dc),
            None => WsTunnelDecision::Passthrough,
        },
        IpAddr::V6(v6) => match dc::dc_from_ipv6(v6) {
            Some(dc) => WsTunnelDecision::Tunnel(dc),
            None => WsTunnelDecision::Passthrough,
        },
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

    use std::net::{Ipv4Addr, TcpListener};

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
