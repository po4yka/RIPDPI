use std::io;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use socket2::Domain;

/// The service-owned protection policy carried across the WARP wire boundary.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WarpSocketProtection {
    Inactive,
    #[default]
    VpnRequired,
}

impl WarpSocketProtection {
    pub const fn is_vpn_required(self) -> bool {
        matches!(self, Self::VpnRequired)
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeEndpoint {
    pub host: String,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub port: i32,
    pub source: String,
}

/// `Debug` is implemented manually to redact the WireGuard `private_key`
/// and the WARP `access_token`. A derived `Debug` would expose them to any
/// `tracing::debug!(?config)` call or panic message that prints the runtime
/// config. Public keys are not secrets and stay visible.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub account_kind: String,
    pub device_id: String,
    pub access_token: String,
    pub client_id: Option<String>,
    pub private_key: String,
    pub public_key: String,
    pub peer_public_key: String,
    pub interface_address_v4: Option<String>,
    pub interface_address_v6: Option<String>,
    pub endpoint: ResolvedWarpRuntimeEndpoint,
    pub route_mode: String,
    pub route_hosts: String,
    pub built_in_rules_enabled: bool,
    pub endpoint_selection_mode: String,
    pub manual_endpoint: WarpManualEndpoint,
    pub scanner_enabled: bool,
    pub scanner_parallelism: i32,
    pub scanner_max_rtt_ms: i32,
    pub amnezia: WarpAmneziaConfig,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub mtu: i32,
    /// Omitted policies fail closed; callers must explicitly select inactive operation.
    #[serde(default)]
    pub socket_protection: WarpSocketProtection,
}

impl std::fmt::Debug for ResolvedWarpRuntimeConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResolvedWarpRuntimeConfig")
            .field("enabled", &self.enabled)
            .field("profile_id", &self.profile_id)
            .field("account_kind", &self.account_kind)
            .field("device_id", &self.device_id)
            .field("socket_protection", &self.socket_protection)
            .field("access_token", &"<redacted>")
            .field("client_id", &self.client_id)
            .field("private_key", &"<redacted>")
            .field("public_key", &self.public_key)
            .field("peer_public_key", &self.peer_public_key)
            .field("interface_address_v4", &self.interface_address_v4)
            .field("interface_address_v6", &self.interface_address_v6)
            .field("endpoint", &self.endpoint)
            .field("route_mode", &self.route_mode)
            .field("route_hosts", &self.route_hosts)
            .field("built_in_rules_enabled", &self.built_in_rules_enabled)
            .field("endpoint_selection_mode", &self.endpoint_selection_mode)
            .field("manual_endpoint", &self.manual_endpoint)
            .field("scanner_enabled", &self.scanner_enabled)
            .field("scanner_parallelism", &self.scanner_parallelism)
            .field("scanner_max_rtt_ms", &self.scanner_max_rtt_ms)
            .field("amnezia", &self.amnezia)
            .field("local_socks_host", &self.local_socks_host)
            .field("local_socks_port", &self.local_socks_port)
            .field("mtu", &self.mtu)
            .finish()
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WarpManualEndpoint {
    pub host: String,
    pub ipv4: String,
    pub ipv6: String,
    pub port: i32,
}

/// WARP's persistent-keepalive default (seconds). WARP historically pinned the
/// interval at 25s; this preserves that when a config omits the field.
pub(crate) const WARP_DEFAULT_PERSISTENT_KEEPALIVE: u16 = 25;

fn default_warp_persistent_keepalive() -> u16 {
    WARP_DEFAULT_PERSISTENT_KEEPALIVE
}

/// A config string that must not reach logs: `Debug` prints `<redacted>` when set.
#[derive(Clone, Default, PartialEq, Eq, Deserialize, Serialize)]
#[serde(transparent)]
pub struct SecretString(pub String);

impl std::fmt::Debug for SecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(if self.0.is_empty() { "\"\"" } else { "<redacted>" })
    }
}

/// AmneziaWG 3.x `[Interface]` keys. Empty strings and `false` mean "not set",
/// so a config without them keeps the AWG 1.x/2.0 wire output.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields, default)]
pub struct AwgV3Config {
    /// Base64 32-byte `HeaderProtectionKey`. A secret: never logged.
    pub header_protection_key: SecretString,
    /// `ContentPaddingAddition`, `lo` or `lo-hi` bytes.
    pub content_padding_addition: String,
    /// `RekeyAfterTime`, `RekeyTimeout`, `RejectAfterTime`, `KeepaliveTimeout` in seconds.
    pub rekey_after_time: String,
    pub rekey_timeout: String,
    pub reject_after_time: String,
    pub keepalive_timeout: String,
    /// `MaxHandshakeAttempts`, `lo` or `lo-hi`.
    pub max_handshake_attempts: String,
    pub random_trailers: bool,
    pub disable_cookies: bool,
}

impl AwgV3Config {
    pub(crate) fn is_set(&self) -> bool {
        *self != Self::default()
    }
}

/// `Debug` is implemented manually to redact the WireGuard `preshared_key`.
/// A derived `Debug` would expose it to any `tracing::debug!(?config)` call
/// or panic message that prints the AmneziaWG config. The `i1..i5` special-junk
/// frames and the junk-shaping knobs are obfuscation parameters, not secrets,
/// and stay visible.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WarpAmneziaConfig {
    pub wire_profile: String,
    pub enabled: bool,
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub h1: String,
    pub h2: String,
    pub h3: String,
    pub h4: String,
    pub s1: i32,
    pub s2: i32,
    pub s3: i32,
    pub s4: i32,
    /// AmneziaWG 3.1 `I1..I5` signature programs. Empty = unset.
    #[serde(default)]
    pub i1: String,
    #[serde(default)]
    pub i2: String,
    #[serde(default)]
    pub i3: String,
    #[serde(default)]
    pub i4: String,
    #[serde(default)]
    pub i5: String,
    /// Base64 (or hex) 32-byte WireGuard preshared key (`[Peer] PresharedKey`).
    /// Empty = no PSK. WARP itself does not use one; a generic AmneziaWG peer
    /// configured through the WARP runtime surface may.
    #[serde(default)]
    pub preshared_key: String,
    /// `[Peer] PersistentKeepalive` in seconds; `0` disables keepalive. Defaults
    /// to WARP's historical 25s pin when omitted from the config JSON.
    #[serde(default = "default_warp_persistent_keepalive")]
    pub persistent_keepalive: u16,
    /// AmneziaWG 3.x keys as a nested `v3` object. Omitted = all off.
    #[serde(default)]
    pub v3: AwgV3Config,
}

impl std::fmt::Debug for WarpAmneziaConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WarpAmneziaConfig")
            .field("enabled", &self.enabled)
            .field("wire_profile", &self.wire_profile)
            .field("jc", &self.jc)
            .field("jmin", &self.jmin)
            .field("jmax", &self.jmax)
            .field("h1", &self.h1)
            .field("h2", &self.h2)
            .field("h3", &self.h3)
            .field("h4", &self.h4)
            .field("s1", &self.s1)
            .field("s2", &self.s2)
            .field("s3", &self.s3)
            .field("s4", &self.s4)
            .field("i1", &self.i1)
            .field("i2", &self.i2)
            .field("i3", &self.i3)
            .field("i4", &self.i4)
            .field("i5", &self.i5)
            .field("preshared_key", &"<redacted>")
            .field("persistent_keepalive", &self.persistent_keepalive)
            .field("v3", &self.v3)
            .finish()
    }
}

impl Default for WarpAmneziaConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            wire_profile: crate::awg_profile::WIRE_PROFILE.into(),
            jc: 0,
            jmin: 0,
            jmax: 0,
            h1: "1".into(),
            h2: "2".into(),
            h3: "3".into(),
            h4: "4".into(),
            s1: 0,
            s2: 0,
            s3: 0,
            s4: 0,
            i1: String::new(),
            i2: String::new(),
            i3: String::new(),
            i4: String::new(),
            i5: String::new(),
            preshared_key: String::new(),
            persistent_keepalive: WARP_DEFAULT_PERSISTENT_KEEPALIVE,
            v3: AwgV3Config::default(),
        }
    }
}

impl WarpAmneziaConfig {
    /// The AWG 3.1 `I1..I5` signature programs, in order, for
    /// feeding `AwgParams::from_config`.
    pub fn signature_programs(&self) -> [&str; 5] {
        [&self.i1, &self.i2, &self.i3, &self.i4, &self.i5]
    }

    /// The configured preshared key, or `None` when empty. Mirrors the
    /// generic-profile mapping in `amneziawg_runtime` so the WARP runtime and a
    /// generic AmneziaWG peer plumb PSK identically into `WireGuardTunnelParams`.
    pub fn preshared_key_opt(&self) -> Option<&str> {
        (!self.preshared_key.is_empty()).then_some(self.preshared_key.as_str())
    }

    /// The configured persistent-keepalive interval, or `None` when `0`
    /// (keepalive disabled).
    pub fn persistent_keepalive_opt(&self) -> Option<u16> {
        (self.persistent_keepalive != 0).then_some(self.persistent_keepalive)
    }
}

/// `Debug` is implemented manually to redact the WireGuard `private_key`.
/// A derived `Debug` would expose it to any `tracing::debug!(?request)` call
/// or panic message that prints the probe request. The peer public key is not
/// a secret and stays visible.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpEndpointProbeRequest {
    pub endpoint: ResolvedWarpRuntimeEndpoint,
    pub private_key: String,
    pub peer_public_key: String,
    pub client_id: Option<String>,
    #[serde(default)]
    pub amnezia: WarpAmneziaConfig,
    pub timeout_ms: u64,
    #[serde(default)]
    pub socket_protection: WarpSocketProtection,
}

impl std::fmt::Debug for WarpEndpointProbeRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WarpEndpointProbeRequest")
            .field("endpoint", &self.endpoint)
            .field("private_key", &"<redacted>")
            .field("peer_public_key", &self.peer_public_key)
            .field("client_id", &self.client_id)
            .field("amnezia", &self.amnezia)
            .field("timeout_ms", &self.timeout_ms)
            .field("socket_protection", &self.socket_protection)
            .finish()
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpEndpointProbeResult {
    pub host: String,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub port: i32,
    pub rtt_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub upstream_rtt_ms: Option<u64>,
    pub profile_id: Option<String>,
    pub last_error: Option<String>,
    pub captured_at: u64,
}

pub(crate) fn preferred_endpoint_candidates(addresses: Vec<SocketAddr>) -> io::Result<Vec<SocketAddr>> {
    if addresses.is_empty() {
        return Err(io::Error::new(io::ErrorKind::AddrNotAvailable, "no endpoint address resolved"));
    }
    let (ipv4, ipv6): (Vec<_>, Vec<_>) = addresses.into_iter().partition(SocketAddr::is_ipv4);
    let (mut ipv4, mut ipv6) = (ipv4.into_iter(), ipv6.into_iter());
    let mut ordered = Vec::with_capacity(ipv4.len() + ipv6.len());
    loop {
        let pair = (ipv4.next(), ipv6.next());
        if pair == (None, None) {
            break;
        }
        ordered.extend(pair.0);
        ordered.extend(pair.1);
    }
    Ok(ordered)
}

fn resolve_host_candidates(host: &str, port: u16, family: Option<Domain>) -> io::Result<Vec<SocketAddr>> {
    let addresses = match host.parse::<IpAddr>() {
        Ok(ip) => vec![SocketAddr::new(ip, port)],
        Err(_) => (host, port).to_socket_addrs()?.collect(),
    };
    preferred_endpoint_candidates(
        addresses
            .into_iter()
            .filter(|address| match family {
                Some(Domain::IPV4) => address.is_ipv4(),
                Some(Domain::IPV6) => address.is_ipv6(),
                _ => true,
            })
            .collect(),
    )
}

fn numeric_endpoint(value: &str, port: u16, family: Option<Domain>) -> io::Result<SocketAddr> {
    let value = value.trim();
    let mut address = value
        .parse::<IpAddr>()
        .map(|ip| SocketAddr::new(ip, port))
        .or_else(|_| value.parse::<SocketAddr>())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid numeric WARP endpoint"))?;
    if family.is_some_and(|family| {
        (family == Domain::IPV4 && !address.is_ipv4()) || (family == Domain::IPV6 && !address.is_ipv6())
    }) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "WARP endpoint address family mismatch"));
    }
    // The provider's numeric fields may carry :0; the separate service port owns routing.
    address.set_port(port);
    Ok(address)
}

/// Upper bound for the system resolver lookup of an endpoint host name.
const ENDPOINT_RESOLVE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

/// Normalize both numeric families without DNS, alternating IPv4 first.
/// Host lookup is bounded by ENDPOINT_RESOLVE_TIMEOUT, not the OS lookup lifetime.
///
/// # Cancel safety:
/// cancel-safe: dropping it discards the result; the owner must not wait for detached DNS work.
pub(crate) async fn resolve_endpoint_candidates(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<Vec<SocketAddr>> {
    let port = u16::try_from(endpoint.port)
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid WARP endpoint port"))?;
    let mut numeric = Vec::new();
    for (value, family) in [(endpoint.ipv4.as_deref(), Domain::IPV4), (endpoint.ipv6.as_deref(), Domain::IPV6)] {
        if let Some(value) = value.filter(|value| !value.trim().is_empty()) {
            numeric.push(numeric_endpoint(value, port, Some(family))?);
        }
    }
    if !numeric.is_empty() {
        return preferred_endpoint_candidates(numeric);
    }
    let host = endpoint.host.trim();
    if let Ok(address) = numeric_endpoint(host, port, None) {
        return Ok(vec![address]);
    }
    if host.is_empty()
        || host.bytes().any(|byte| matches!(byte, b':' | b'[' | b']'))
        || host.bytes().all(|byte| byte.is_ascii_digit() || byte == b'.')
    {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid WARP endpoint host"));
    }
    let host = host.to_owned();
    let lookup = tokio::task::spawn_blocking(move || resolve_host_candidates(&host, port, None));
    match tokio::time::timeout(ENDPOINT_RESOLVE_TIMEOUT, lookup).await {
        Ok(Ok(resolved)) => resolved,
        Ok(Err(join_error)) => Err(io::Error::other(format!("endpoint lookup task failed: {join_error}"))),
        Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, "endpoint lookup timed out")),
    }
}

/// Select the preferred address for a one-shot endpoint probe.
///
/// # Cancel safety:
/// cancel-safe: dropping it discards the result of the bounded resolver lookup.
pub(crate) async fn resolve_endpoint(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<SocketAddr> {
    resolve_endpoint_candidates(endpoint)
        .await?
        .into_iter()
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no endpoint address resolved"))
}

pub(crate) fn parse_ipv4_cidr(value: Option<&str>) -> Option<IpAddr> {
    let address = ripdpi_packets::ip::parse_cidr(value?, Some((32, 128))).ok()?.addr();
    address.is_ipv4().then_some(address)
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn endpoint(host: &str, ipv4: Option<&str>, ipv6: Option<&str>, port: i32) -> ResolvedWarpRuntimeEndpoint {
        ResolvedWarpRuntimeEndpoint {
            host: host.to_string(),
            ipv4: ipv4.map(ToOwned::to_owned),
            ipv6: ipv6.map(ToOwned::to_owned),
            port,
            source: "test".to_string(),
        }
    }

    #[test]
    fn endpoint_candidates_accept_aaaa_only_and_keep_ipv4_preference() {
        let v6: SocketAddr = "[2001:db8::1]:51820".parse().unwrap();
        let v4: SocketAddr = "192.0.2.1:51820".parse().unwrap();
        assert_eq!(preferred_endpoint_candidates(vec![v6]).unwrap(), vec![v6]);
        assert_eq!(preferred_endpoint_candidates(vec![v6, v4]).unwrap(), vec![v4, v6]);
        let v4b: SocketAddr = "192.0.2.2:51820".parse().unwrap();
        let v4c: SocketAddr = "192.0.2.3:51820".parse().unwrap();
        let v4d: SocketAddr = "192.0.2.4:51820".parse().unwrap();
        let v6b: SocketAddr = "[2001:db8::2]:51820".parse().unwrap();
        assert_eq!(preferred_endpoint_candidates(vec![v6, v4, v6b, v4b]).unwrap(), vec![v4, v6, v4b, v6b]);
        assert_eq!(preferred_endpoint_candidates(vec![v4, v4b, v4c, v4d, v6]).unwrap(), vec![v4, v6, v4b, v4c, v4d]);
        assert_eq!(preferred_endpoint_candidates(Vec::new()).unwrap_err().kind(), io::ErrorKind::AddrNotAvailable);
    }

    /// # Cancel safety:
    /// cancel-safe: this test only awaits a bounded numeric-address lookup.
    #[tokio::test]
    async fn endpoint_candidates_keep_both_explicit_families() {
        let resolved =
            resolve_endpoint_candidates(&endpoint("unused.invalid", Some("192.0.2.1"), Some("2001:db8::1"), 51820))
                .await
                .unwrap();
        assert_eq!(
            resolved,
            vec!["192.0.2.1:51820".parse::<SocketAddr>().unwrap(), "[2001:db8::1]:51820".parse().unwrap()]
        );
    }

    #[tokio::test]
    async fn resolve_endpoint_prefers_explicit_ipv4() {
        let endpoint = endpoint("203.0.113.99", Some("192.0.2.44"), Some("2001:db8::44"), 2408);

        let resolved = resolve_endpoint(&endpoint).await.expect("endpoint");

        assert_eq!(resolved, SocketAddr::from(([192, 0, 2, 44], 2408)));
    }

    #[tokio::test]
    async fn resolve_endpoint_uses_ipv6_when_ipv4_is_absent() {
        let endpoint = endpoint("203.0.113.99", None, Some("2001:db8::44"), 2408);

        let resolved = resolve_endpoint(&endpoint).await.expect("endpoint");

        assert_eq!(resolved, "[2001:db8::44]:2408".parse::<SocketAddr>().expect("ipv6 socket addr"));
    }

    // No Tokio runtime exists here: a literal must finish in one poll, without
    // spawning a blocking lookup or consulting the system resolver.
    fn resolve_without_dns(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<SocketAddr> {
        use std::future::Future;
        use std::task::{Context, Poll, Waker};
        let mut future = std::pin::pin!(resolve_endpoint(endpoint));
        match future.as_mut().poll(&mut Context::from_waker(Waker::noop())) {
            Poll::Ready(result) => result,
            Poll::Pending => panic!("numeric endpoint must not start DNS"),
        }
    }

    #[test]
    fn numeric_socket_addresses_use_the_service_port_without_dns() {
        for input in ["127.0.0.1", "127.0.0.1:0", " 127.0.0.1:65535 "] {
            let explicit = endpoint("must-not-resolve.invalid", Some(input), None, 2408);
            assert_eq!(resolve_without_dns(&explicit).expect("IPv4 endpoint"), "127.0.0.1:2408".parse().unwrap());
            let host = endpoint(input, None, None, 2408);
            assert_eq!(resolve_without_dns(&host).expect("IPv4 host"), "127.0.0.1:2408".parse().unwrap());
        }
        for input in ["::1", "[::1]:0", " [::1]:65535 "] {
            let explicit = endpoint("must-not-resolve.invalid", None, Some(input), 2408);
            assert_eq!(resolve_without_dns(&explicit).expect("IPv6 endpoint"), "[::1]:2408".parse().unwrap());
            let host = endpoint(input, None, None, 2408);
            assert_eq!(resolve_without_dns(&host).expect("IPv6 host"), "[::1]:2408".parse().unwrap());
        }
        let scoped = endpoint("must-not-resolve.invalid", None, Some("[fe80::1%7]:0"), 2408);
        assert_eq!(resolve_without_dns(&scoped).expect("scope"), "[fe80::1%7]:2408".parse().unwrap());
    }

    #[test]
    fn malformed_numeric_fields_and_service_ports_fail_before_dns() {
        for input in ["127.0.0.1:invalid", "999.0.0.1", "::1", "lookup.invalid"] {
            let candidate = endpoint("must-not-resolve.invalid", Some(input), None, 2408);
            assert_eq!(resolve_without_dns(&candidate).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        }
        for input in ["[::1]:invalid", "[::1", "127.0.0.1", "lookup.invalid"] {
            let candidate = endpoint("must-not-resolve.invalid", None, Some(input), 2408);
            assert_eq!(resolve_without_dns(&candidate).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        }
        for input in ["", " ", "999.0.0.1", "127.0.0.1:invalid", "[::1", "::g"] {
            let candidate = endpoint(input, None, None, 2408);
            assert_eq!(resolve_without_dns(&candidate).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        }
        for port in [-1, 0, 65_536] {
            let candidate = endpoint("127.0.0.1", None, None, port);
            assert_eq!(resolve_without_dns(&candidate).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        }
    }

    #[test]
    fn parse_ipv4_cidr_extracts_address_only() {
        assert_eq!(parse_ipv4_cidr(Some("172.16.0.2/32")), Some(IpAddr::V4(Ipv4Addr::new(172, 16, 0, 2))));
        for value in [
            "not-an-ip/32",
            "172.16.0.2/garbage",
            "172.16.0.2/33",
            "172.16.0.2/+24",
            "172.16.0.2/024",
            "172.16.0.2/",
            "172.16.0.2/24/24",
            "::1/32",
        ] {
            assert_eq!(parse_ipv4_cidr(Some(value)), None, "{value}");
        }
        assert_eq!(parse_ipv4_cidr(Some("192.0.2.129/24")), Some(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 129))));
        assert_eq!(parse_ipv4_cidr(None), None);
    }

    #[test]
    fn amnezia_keepalive_defaults_to_warp_pin_when_omitted() {
        // A config JSON that omits keepalive/PSK (every pre-A3 WARP config)
        // must deserialize to the historical 25s pin and no PSK.
        let cfg: WarpAmneziaConfig = serde_json::from_str(
            r#"{
            "enabled": false, "jc": 0, "jmin": 0, "jmax": 0,
            "wireProfile": "awg-3.1-ranges-v1", "h1": "1", "h2": "2", "h3": "3", "h4": "4",
            "s1": 0, "s2": 0, "s3": 0, "s4": 0
        }"#,
        )
        .expect("parse");
        assert_eq!(cfg.persistent_keepalive, WARP_DEFAULT_PERSISTENT_KEEPALIVE);
        assert_eq!(cfg.persistent_keepalive_opt(), Some(25));
        assert_eq!(cfg.preshared_key_opt(), None);
        // The struct Default mirrors the serde default for both knobs.
        assert_eq!(WarpAmneziaConfig::default().persistent_keepalive, 25);
    }

    #[test]
    fn amnezia_wire_contract_refuses_legacy_and_unknown_fields() {
        let valid = serde_json::to_value(WarpAmneziaConfig::default()).unwrap();
        let mut legacy_header = valid.clone();
        legacy_header["h1"] = serde_json::json!(1);
        assert!(serde_json::from_value::<WarpAmneziaConfig>(legacy_header).is_err());
        let mut missing_profile = valid.clone();
        missing_profile.as_object_mut().unwrap().remove("wireProfile");
        assert!(serde_json::from_value::<WarpAmneziaConfig>(missing_profile).is_err());
        let mut unsupported = valid;
        unsupported["disableCookies"] = serde_json::json!(true);
        assert!(serde_json::from_value::<WarpAmneziaConfig>(unsupported).is_err());
    }

    #[test]
    fn amnezia_psk_and_keepalive_round_trip_and_project() {
        let cfg =
            WarpAmneziaConfig { preshared_key: "abc123".to_string(), persistent_keepalive: 0, ..Default::default() };
        // 0 keepalive disables the option; a non-empty PSK projects to Some.
        assert_eq!(cfg.persistent_keepalive_opt(), None);
        assert_eq!(cfg.preshared_key_opt(), Some("abc123"));

        let json = serde_json::to_string(&cfg).expect("serialize");
        assert!(json.contains("\"presharedKey\":\"abc123\""));
        assert!(json.contains("\"persistentKeepalive\":0"));
        let again: WarpAmneziaConfig = serde_json::from_str(&json).expect("reparse");
        assert_eq!(again.preshared_key, "abc123");
        assert_eq!(again.persistent_keepalive, 0);
    }

    // --- credential redaction in Debug ---
    //
    // A derived `Debug` on the credential-bearing config structs would echo the
    // WireGuard `private_key` / `preshared_key` and the WARP `access_token` to
    // any `tracing::debug!(?config)` call or panic message (logcat). The manual
    // `Debug` impls render those fields as "<redacted>". These tests fail if the
    // manual impl is removed (e.g. replaced with `#[derive(Debug)]`).

    const SECRET_PRIVATE_KEY: &str = "wg-private-key-do-not-log-fixture";
    const SECRET_PRESHARED_KEY: &str = "wg-preshared-key-do-not-log-fixture";
    const SECRET_ACCESS_TOKEN: &str = "warp-access-token-do-not-log-fixture";

    fn sample_amnezia() -> WarpAmneziaConfig {
        WarpAmneziaConfig { enabled: true, preshared_key: SECRET_PRESHARED_KEY.to_owned(), ..Default::default() }
    }

    fn sample_runtime_config() -> ResolvedWarpRuntimeConfig {
        ResolvedWarpRuntimeConfig {
            enabled: true,
            profile_id: "profile-1".to_owned(),
            account_kind: "free".to_owned(),
            device_id: "device-1".to_owned(),
            access_token: SECRET_ACCESS_TOKEN.to_owned(),
            client_id: Some("client-1".to_owned()),
            private_key: SECRET_PRIVATE_KEY.to_owned(),
            public_key: "wg-public-key".to_owned(),
            peer_public_key: "wg-peer-public-key".to_owned(),
            interface_address_v4: Some("172.16.0.2/32".to_owned()),
            interface_address_v6: None,
            endpoint: endpoint("engage.cloudflareclient.com", Some("162.159.192.1"), None, 2408),
            route_mode: "all".to_owned(),
            route_hosts: String::new(),
            built_in_rules_enabled: true,
            endpoint_selection_mode: "auto".to_owned(),
            manual_endpoint: WarpManualEndpoint::default(),
            scanner_enabled: false,
            scanner_parallelism: 1,
            scanner_max_rtt_ms: 1_000,
            amnezia: sample_amnezia(),
            local_socks_host: "127.0.0.1".to_owned(),
            local_socks_port: 1080,
            mtu: 1280,
            socket_protection: WarpSocketProtection::default(),
        }
    }

    fn sample_probe_request() -> WarpEndpointProbeRequest {
        WarpEndpointProbeRequest {
            endpoint: endpoint("engage.cloudflareclient.com", Some("162.159.192.1"), None, 2408),
            private_key: SECRET_PRIVATE_KEY.to_owned(),
            peer_public_key: "wg-peer-public-key".to_owned(),
            client_id: Some("client-1".to_owned()),
            amnezia: sample_amnezia(),
            timeout_ms: 5_000,
            socket_protection: WarpSocketProtection::default(),
        }
    }

    #[test]
    fn omitted_socket_protection_is_vpn_required() {
        let mut runtime = serde_json::to_value(sample_runtime_config()).expect("runtime JSON");
        runtime.as_object_mut().expect("object").remove("socketProtection");
        let decoded: ResolvedWarpRuntimeConfig = serde_json::from_value(runtime).expect("runtime");
        assert!(decoded.socket_protection.is_vpn_required());
        let mut probe = serde_json::to_value(sample_probe_request()).expect("probe JSON");
        probe.as_object_mut().expect("object").remove("socketProtection");
        let decoded: WarpEndpointProbeRequest = serde_json::from_value(probe).expect("probe");
        assert!(decoded.socket_protection.is_vpn_required());
    }

    #[test]
    fn inactive_socket_protection_must_be_explicit() {
        let mut runtime = sample_runtime_config();
        runtime.socket_protection = WarpSocketProtection::Inactive;
        let encoded = serde_json::to_value(runtime).expect("runtime JSON");
        assert_eq!(encoded["socketProtection"], "inactive");
        let decoded: ResolvedWarpRuntimeConfig = serde_json::from_value(encoded).expect("runtime");
        assert!(!decoded.socket_protection.is_vpn_required());
        let mut probe = sample_probe_request();
        probe.socket_protection = WarpSocketProtection::Inactive;
        let encoded = serde_json::to_value(probe).expect("probe JSON");
        assert_eq!(encoded["socketProtection"], "inactive");
        let decoded: WarpEndpointProbeRequest = serde_json::from_value(encoded).expect("probe");
        assert!(!decoded.socket_protection.is_vpn_required());
        for invalid in [serde_json::json!("unknown"), serde_json::Value::Null, serde_json::json!(false)] {
            assert!(serde_json::from_value::<WarpSocketProtection>(invalid).is_err());
        }
    }

    #[test]
    fn redacted_debug_omits_runtime_config_secrets() {
        let cfg = sample_runtime_config();
        // Both the `Debug`-trait render and the `{:?}` format macro must redact.
        let via_debug = format!("{:?}", &cfg as &dyn std::fmt::Debug);
        let via_macro = format!("{cfg:?}");
        for dbg in [&via_debug, &via_macro] {
            assert!(!dbg.contains(SECRET_PRIVATE_KEY), "Debug exposes private_key: {dbg}");
            assert!(!dbg.contains(SECRET_ACCESS_TOKEN), "Debug exposes access_token: {dbg}");
            assert!(!dbg.contains(SECRET_PRESHARED_KEY), "Debug exposes preshared_key: {dbg}");
            assert!(dbg.contains("<redacted>"), "redaction marker should be present: {dbg}");
            // Non-secret fields stay visible.
            assert!(dbg.contains("profile-1"), "profile_id should remain visible: {dbg}");
            assert!(dbg.contains("wg-public-key"), "public_key should remain visible: {dbg}");
        }
    }

    #[test]
    fn redacted_debug_omits_amnezia_preshared_key() {
        let cfg = sample_amnezia();
        let via_debug = format!("{:?}", &cfg as &dyn std::fmt::Debug);
        let via_macro = format!("{cfg:?}");
        for dbg in [&via_debug, &via_macro] {
            assert!(!dbg.contains(SECRET_PRESHARED_KEY), "Debug exposes preshared_key: {dbg}");
            assert!(dbg.contains("<redacted>"), "redaction marker should be present: {dbg}");
            // The junk-shaping knobs are not secrets and stay visible.
            assert!(dbg.contains("enabled: true"), "enabled should remain visible: {dbg}");
        }
    }

    #[test]
    fn redacted_debug_omits_probe_request_private_key() {
        let cfg = sample_probe_request();
        let via_debug = format!("{:?}", &cfg as &dyn std::fmt::Debug);
        let via_macro = format!("{cfg:?}");
        for dbg in [&via_debug, &via_macro] {
            assert!(!dbg.contains(SECRET_PRIVATE_KEY), "Debug exposes private_key: {dbg}");
            // The nested amnezia config's PSK must also stay redacted.
            assert!(!dbg.contains(SECRET_PRESHARED_KEY), "Debug exposes nested preshared_key: {dbg}");
            assert!(dbg.contains("<redacted>"), "redaction marker should be present: {dbg}");
            assert!(dbg.contains("wg-peer-public-key"), "peer_public_key should remain visible: {dbg}");
        }
    }
}
