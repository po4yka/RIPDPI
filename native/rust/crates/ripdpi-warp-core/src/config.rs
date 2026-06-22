use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use socket2::Domain;

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
}

impl std::fmt::Debug for ResolvedWarpRuntimeConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResolvedWarpRuntimeConfig")
            .field("enabled", &self.enabled)
            .field("profile_id", &self.profile_id)
            .field("account_kind", &self.account_kind)
            .field("device_id", &self.device_id)
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

/// `Debug` is implemented manually to redact the WireGuard `preshared_key`.
/// A derived `Debug` would expose it to any `tracing::debug!(?config)` call
/// or panic message that prints the AmneziaWG config. The `i1..i5` special-junk
/// frames and the junk-shaping knobs are obfuscation parameters, not secrets,
/// and stay visible.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpAmneziaConfig {
    pub enabled: bool,
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub h1: i64,
    pub h2: i64,
    pub h3: i64,
    pub h4: i64,
    pub s1: i32,
    pub s2: i32,
    pub s3: i32,
    pub s4: i32,
    /// AmneziaWG 2.0 `I1..I5` special-junk frames as hex strings. Empty = unset.
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
}

impl std::fmt::Debug for WarpAmneziaConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WarpAmneziaConfig")
            .field("enabled", &self.enabled)
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
            .finish()
    }
}

impl Default for WarpAmneziaConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            jc: 0,
            jmin: 0,
            jmax: 0,
            h1: 0,
            h2: 0,
            h3: 0,
            h4: 0,
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
        }
    }
}

impl WarpAmneziaConfig {
    /// The AWG 2.0 `I1..I5` special-junk frames as hex strings, in order, for
    /// feeding `AwgParams::from_config`.
    pub fn special_junk_hex(&self) -> [&str; 5] {
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

pub(crate) fn resolve_sync_host(host: &str, port: u16) -> io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    (host, port)
        .to_socket_addrs()?
        .find(SocketAddr::is_ipv4)
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no IPv4 address resolved"))
}

pub(crate) fn resolve_host_for_family(host: &str, port: u16, family: Domain) -> io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        let matches_family = match family {
            Domain::IPV4 => ip.is_ipv4(),
            Domain::IPV6 => ip.is_ipv6(),
            _ => true,
        };
        if matches_family {
            return Ok(SocketAddr::new(ip, port));
        }
    }
    let predicate: fn(&SocketAddr) -> bool = match family {
        Domain::IPV6 => SocketAddr::is_ipv6,
        _ => SocketAddr::is_ipv4,
    };
    (host, port)
        .to_socket_addrs()?
        .find(predicate)
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no endpoint address resolved"))
}

pub(crate) async fn resolve_endpoint(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<SocketAddr> {
    let port = u16::try_from(endpoint.port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid WARP endpoint port"))?;
    if let Some(ipv4) = endpoint.ipv4.as_deref().filter(|value| !value.is_empty()) {
        return resolve_host_for_family(ipv4, port, Domain::IPV4);
    }
    if let Some(ipv6) = endpoint.ipv6.as_deref().filter(|value| !value.is_empty()) {
        return resolve_host_for_family(ipv6, port, Domain::IPV6);
    }
    resolve_sync_host(&endpoint.host, port)
}

pub(crate) fn parse_ipv4_cidr(value: Option<&str>) -> Option<IpAddr> {
    value.and_then(|raw| raw.split('/').next()).and_then(|addr| addr.parse::<Ipv4Addr>().ok()).map(IpAddr::V4)
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn endpoint(host: &str, ipv4: Option<&str>, ipv6: Option<&str>, port: i32) -> ResolvedWarpRuntimeEndpoint {
        ResolvedWarpRuntimeEndpoint {
            host: host.to_string(),
            ipv4: ipv4.map(ToOwned::to_owned),
            ipv6: ipv6.map(ToOwned::to_owned),
            port,
            source: "test".to_string(),
        }
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

    #[test]
    fn parse_ipv4_cidr_extracts_address_only() {
        assert_eq!(parse_ipv4_cidr(Some("172.16.0.2/32")), Some(IpAddr::V4(Ipv4Addr::new(172, 16, 0, 2))));
        assert_eq!(parse_ipv4_cidr(Some("not-an-ip/32")), None);
        assert_eq!(parse_ipv4_cidr(None), None);
    }

    #[test]
    fn amnezia_keepalive_defaults_to_warp_pin_when_omitted() {
        // A config JSON that omits keepalive/PSK (every pre-A3 WARP config)
        // must deserialize to the historical 25s pin and no PSK.
        let cfg: WarpAmneziaConfig = serde_json::from_str(
            r#"{
            "enabled": false, "jc": 0, "jmin": 0, "jmax": 0,
            "h1": 0, "h2": 0, "h3": 0, "h4": 0,
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
