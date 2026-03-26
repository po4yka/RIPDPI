use ripdpi_tunnel_config::{Config, MapDnsConfig, MiscConfig, Socks5Config, TunnelConfig};
use serde::Deserialize;

fn default_tunnel_name() -> String {
    "tun0".to_string()
}

fn default_tunnel_mtu() -> u32 {
    1500
}

fn default_multi_queue() -> bool {
    false
}

fn default_socks5_address() -> String {
    "127.0.0.1".to_string()
}

fn default_socks5_udp() -> Option<String> {
    Some("udp".to_string())
}

fn default_task_stack_size() -> u32 {
    81_920
}

fn default_log_level() -> String {
    "warn".to_string()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelLogContext {
    pub(crate) runtime_id: Option<String>,
    pub(crate) mode: Option<String>,
    pub(crate) policy_signature: Option<String>,
    pub(crate) fingerprint_hash: Option<String>,
    pub(crate) diagnostics_session_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelConfigPayload {
    #[serde(default = "default_tunnel_name")]
    pub(crate) tunnel_name: String,
    #[serde(default = "default_tunnel_mtu")]
    pub(crate) tunnel_mtu: u32,
    #[serde(default = "default_multi_queue")]
    pub(crate) multi_queue: bool,
    pub(crate) tunnel_ipv4: Option<String>,
    pub(crate) tunnel_ipv6: Option<String>,
    #[serde(default = "default_socks5_address")]
    pub(crate) socks5_address: String,
    pub(crate) socks5_port: u16,
    #[serde(default = "default_socks5_udp")]
    pub(crate) socks5_udp: Option<String>,
    pub(crate) socks5_udp_address: Option<String>,
    pub(crate) socks5_pipeline: Option<bool>,
    pub(crate) username: Option<String>,
    pub(crate) password: Option<String>,
    pub(crate) mapdns_address: Option<String>,
    pub(crate) mapdns_port: Option<u16>,
    pub(crate) mapdns_network: Option<String>,
    pub(crate) mapdns_netmask: Option<String>,
    pub(crate) mapdns_cache_size: Option<u32>,
    pub(crate) encrypted_dns_resolver_id: Option<String>,
    pub(crate) encrypted_dns_protocol: Option<String>,
    pub(crate) encrypted_dns_host: Option<String>,
    pub(crate) encrypted_dns_port: Option<u16>,
    pub(crate) encrypted_dns_tls_server_name: Option<String>,
    pub(crate) encrypted_dns_doh_url: Option<String>,
    pub(crate) encrypted_dns_dnscrypt_provider_name: Option<String>,
    pub(crate) encrypted_dns_dnscrypt_public_key: Option<String>,
    // Deprecated compatibility fields kept for older payloads.
    pub(crate) doh_resolver_id: Option<String>,
    pub(crate) doh_url: Option<String>,
    #[serde(default)]
    pub(crate) doh_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub(crate) encrypted_dns_bootstrap_ips: Vec<String>,
    pub(crate) dns_query_timeout_ms: Option<u32>,
    pub(crate) resolver_fallback_active: Option<bool>,
    pub(crate) resolver_fallback_reason: Option<String>,
    #[serde(default = "default_task_stack_size")]
    pub(crate) task_stack_size: u32,
    pub(crate) tcp_buffer_size: Option<u32>,
    pub(crate) udp_recv_buffer_size: Option<u32>,
    pub(crate) udp_copy_buffer_nums: Option<u32>,
    pub(crate) max_session_count: Option<u32>,
    pub(crate) connect_timeout_ms: Option<u32>,
    pub(crate) tcp_read_write_timeout_ms: Option<u32>,
    pub(crate) udp_read_write_timeout_ms: Option<u32>,
    #[serde(default = "default_log_level")]
    pub(crate) log_level: String,
    pub(crate) limit_nofile: Option<u32>,
    #[serde(default)]
    pub(crate) filter_injected_resets: Option<bool>,
    #[serde(default)]
    pub(crate) log_context: Option<TunnelLogContext>,
}

trait BlankCheck {
    fn is_blank(&self) -> bool;
}

impl BlankCheck for String {
    fn is_blank(&self) -> bool {
        self.trim().is_empty()
    }
}

fn trim_non_empty(value: Option<String>) -> Option<String> {
    value.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty())
}

pub(crate) fn sanitize_log_context(log_context: Option<TunnelLogContext>) -> Option<TunnelLogContext> {
    let mut log_context = log_context?;
    log_context.runtime_id = trim_non_empty(log_context.runtime_id);
    log_context.mode = trim_non_empty(log_context.mode).map(|value| value.to_ascii_lowercase());
    log_context.policy_signature = trim_non_empty(log_context.policy_signature);
    log_context.fingerprint_hash = trim_non_empty(log_context.fingerprint_hash);
    log_context.diagnostics_session_id = trim_non_empty(log_context.diagnostics_session_id);
    if log_context.runtime_id.is_none()
        && log_context.mode.is_none()
        && log_context.policy_signature.is_none()
        && log_context.fingerprint_hash.is_none()
        && log_context.diagnostics_session_id.is_none()
    {
        None
    } else {
        Some(log_context)
    }
}

pub(crate) fn config_from_payload(payload: TunnelConfigPayload) -> Result<Config, String> {
    if payload.socks5_address.is_blank() {
        return Err("socks5Address must not be blank".to_string());
    }
    if payload.tunnel_name.is_blank() {
        return Err("tunnelName must not be blank".to_string());
    }

    // Port validation
    if payload.socks5_port == 0 {
        return Err("socks5Port must be non-zero".to_string());
    }

    // MTU bounds (68 = minimum IPv4 MTU, 65535 = max IP packet)
    if payload.tunnel_mtu < 68 || payload.tunnel_mtu > 65535 {
        return Err(format!("tunnelMtu must be between 68 and 65535, got {}", payload.tunnel_mtu));
    }

    // Task stack size bounds (minimum 8KB, maximum 16MB)
    if payload.task_stack_size < 8_192 || payload.task_stack_size > 16_777_216 {
        return Err(format!("taskStackSize must be between 8192 and 16777216, got {}", payload.task_stack_size));
    }

    // Optional numeric bounds (only validate if present)
    if let Some(port) = payload.mapdns_port {
        if port == 0 {
            return Err("mapdnsPort must be non-zero".to_string());
        }
    }
    if let Some(timeout) = payload.connect_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("connectTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(timeout) = payload.tcp_read_write_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("tcpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(timeout) = payload.udp_read_write_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("udpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(limit) = payload.limit_nofile {
        if limit < 64 || limit > 1_048_576 {
            return Err(format!("limitNofile must be between 64 and 1048576, got {limit}"));
        }
    }
    if let Some(max) = payload.max_session_count {
        if max == 0 || max > 100_000 {
            return Err(format!("maxSessionCount must be between 1 and 100000, got {max}"));
        }
    }
    if let Some(size) = payload.tcp_buffer_size {
        if size == 0 || size > 16_777_216 {
            return Err(format!("tcpBufferSize must be between 1 and 16777216, got {size}"));
        }
    }
    if let Some(size) = payload.udp_recv_buffer_size {
        if size == 0 || size > 16_777_216 {
            return Err(format!("udpRecvBufferSize must be between 1 and 16777216, got {size}"));
        }
    }

    let mut misc =
        MiscConfig { task_stack_size: payload.task_stack_size, log_level: payload.log_level, ..MiscConfig::default() };
    if let Some(value) = payload.tcp_buffer_size {
        misc.tcp_buffer_size = value;
    }
    if let Some(value) = payload.udp_recv_buffer_size {
        misc.udp_recv_buffer_size = value;
    }
    if let Some(value) = payload.udp_copy_buffer_nums {
        misc.udp_copy_buffer_nums = value;
    }
    if let Some(value) = payload.max_session_count {
        misc.max_session_count = value;
    }
    if let Some(value) = payload.connect_timeout_ms {
        misc.connect_timeout = value;
    }
    if let Some(value) = payload.tcp_read_write_timeout_ms {
        misc.tcp_read_write_timeout = value;
    }
    if let Some(value) = payload.udp_read_write_timeout_ms {
        misc.udp_read_write_timeout = value;
    }
    if let Some(value) = payload.limit_nofile {
        misc.limit_nofile = value;
    }
    if let Some(value) = payload.filter_injected_resets {
        misc.filter_injected_resets = value;
    }

    let mapdns = payload.mapdns_address.map(|address| MapDnsConfig {
        address,
        port: payload.mapdns_port.unwrap_or(53),
        network: payload.mapdns_network,
        netmask: payload.mapdns_netmask,
        cache_size: payload.mapdns_cache_size.unwrap_or(10_000),
        resolver_id: payload.encrypted_dns_resolver_id.or(payload.doh_resolver_id),
        encrypted_dns_protocol: payload.encrypted_dns_protocol,
        encrypted_dns_host: payload.encrypted_dns_host,
        encrypted_dns_port: payload.encrypted_dns_port,
        encrypted_dns_tls_server_name: payload.encrypted_dns_tls_server_name,
        encrypted_dns_bootstrap_ips: payload.encrypted_dns_bootstrap_ips,
        encrypted_dns_doh_url: payload.encrypted_dns_doh_url,
        encrypted_dns_dnscrypt_provider_name: payload.encrypted_dns_dnscrypt_provider_name,
        encrypted_dns_dnscrypt_public_key: payload.encrypted_dns_dnscrypt_public_key,
        doh_url: payload.doh_url,
        doh_bootstrap_ips: payload.doh_bootstrap_ips,
        dns_query_timeout_ms: payload.dns_query_timeout_ms.unwrap_or(4_000),
        resolver_fallback_active: payload.resolver_fallback_active.unwrap_or(false),
        resolver_fallback_reason: payload.resolver_fallback_reason,
    });

    Ok(Config {
        tunnel: TunnelConfig {
            name: payload.tunnel_name,
            mtu: payload.tunnel_mtu,
            multi_queue: payload.multi_queue,
            ipv4: payload.tunnel_ipv4,
            ipv6: payload.tunnel_ipv6,
            post_up_script: None,
            pre_down_script: None,
        },
        socks5: Socks5Config {
            port: payload.socks5_port,
            address: payload.socks5_address,
            udp: payload.socks5_udp,
            udp_address: payload.socks5_udp_address,
            pipeline: payload.socks5_pipeline,
            username: payload.username,
            password: payload.password,
            mark: None,
        },
        mapdns,
        misc,
    })
}

pub(crate) fn parse_tunnel_config_json(json: &str) -> Result<TunnelConfigPayload, String> {
    serde_json::from_str::<TunnelConfigPayload>(json).map_err(|err| format!("Invalid tunnel config JSON: {err}"))
}

pub(crate) fn mapdns_resolver_protocol(mapdns: &MapDnsConfig) -> Option<String> {
    mapdns.encrypted_dns_protocol.clone().or_else(|| mapdns.doh_url.as_ref().map(|_| "doh".to_string()))
}

#[cfg(test)]
pub(crate) fn sample_payload() -> TunnelConfigPayload {
    TunnelConfigPayload {
        tunnel_name: "tun0".to_string(),
        tunnel_mtu: 1500,
        multi_queue: false,
        tunnel_ipv4: None,
        tunnel_ipv6: None,
        socks5_address: "127.0.0.1".to_string(),
        socks5_port: 1080,
        socks5_udp: Some("udp".to_string()),
        socks5_udp_address: None,
        socks5_pipeline: None,
        username: None,
        password: None,
        mapdns_address: None,
        mapdns_port: None,
        mapdns_network: None,
        mapdns_netmask: None,
        mapdns_cache_size: None,
        encrypted_dns_resolver_id: None,
        encrypted_dns_protocol: None,
        encrypted_dns_host: None,
        encrypted_dns_port: None,
        encrypted_dns_tls_server_name: None,
        encrypted_dns_doh_url: None,
        encrypted_dns_dnscrypt_provider_name: None,
        encrypted_dns_dnscrypt_public_key: None,
        doh_resolver_id: None,
        doh_url: None,
        doh_bootstrap_ips: Vec::new(),
        encrypted_dns_bootstrap_ips: Vec::new(),
        dns_query_timeout_ms: None,
        resolver_fallback_active: None,
        resolver_fallback_reason: None,
        task_stack_size: 81_920,
        tcp_buffer_size: None,
        udp_recv_buffer_size: None,
        udp_copy_buffer_nums: None,
        max_session_count: None,
        connect_timeout_ms: None,
        tcp_read_write_timeout_ms: None,
        udp_read_write_timeout_ms: None,
        log_level: "warn".to_string(),
        limit_nofile: None,
        filter_injected_resets: None,
        log_context: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::collection::vec;
    use proptest::prelude::*;

    fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
        vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
    }

    fn non_blank_string(max_len: usize) -> impl Strategy<Value = String> {
        lossy_string(max_len).prop_filter("string must not be blank", |value| !value.trim().is_empty())
    }

    fn ipv4_address() -> impl Strategy<Value = String> {
        (1u8..=223, any::<u8>(), any::<u8>(), 1u8..=254).prop_map(|(a, b, c, d)| format!("{a}.{b}.{c}.{d}"))
    }

    fn tunnel_payload_strategy() -> impl Strategy<Value = TunnelConfigPayload> {
        // proptest implements Strategy for tuples up to 12 elements;
        // nest into sub-tuples to stay within that limit.
        (
            (
                lossy_string(24),
                68u32..65_536,
                any::<bool>(),
                prop::option::of(ipv4_address()),
                prop::option::of(prop_oneof![Just("fd00::1".to_string()), Just("2001:db8::1".to_string())]),
                lossy_string(32),
                1u16..=u16::MAX,
                prop::option::of(lossy_string(12)),
                prop::option::of(ipv4_address()),
                prop::option::of(any::<bool>()),
                prop::option::of(lossy_string(16)),
                prop::option::of(lossy_string(16)),
            ),
            (
                prop::option::of(ipv4_address()),
                prop::option::of(1u16..=u16::MAX),
                prop::option::of(Just("172.16.0.0".to_string())),
                prop::option::of(Just("255.240.0.0".to_string())),
                prop::option::of(1u32..50_001),
                8_192u32..262_145,
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..1025),
                prop::option::of(1u32..100_001),
            ),
            // connect_timeout, tcp/udp rw timeouts, log_level, limit_nofile
            (
                prop::option::of(1u32..300_001),
                prop::option::of(1u32..300_001),
                prop::option::of(1u32..300_001),
                prop_oneof![
                    Just("trace".to_string()),
                    Just("debug".to_string()),
                    Just("info".to_string()),
                    Just("warn".to_string()),
                    Just("error".to_string()),
                ],
                prop::option::of(128u32..65_536),
            ),
        )
            .prop_map(
                |(
                    (
                        tunnel_name,
                        tunnel_mtu,
                        multi_queue,
                        tunnel_ipv4,
                        tunnel_ipv6,
                        socks5_address,
                        socks5_port,
                        socks5_udp,
                        socks5_udp_address,
                        socks5_pipeline,
                        username,
                        password,
                    ),
                    (
                        mapdns_address,
                        mapdns_port,
                        mapdns_network,
                        mapdns_netmask,
                        mapdns_cache_size,
                        task_stack_size,
                        tcp_buffer_size,
                        udp_recv_buffer_size,
                        udp_copy_buffer_nums,
                        max_session_count,
                    ),
                    (connect_timeout_ms, tcp_read_write_timeout_ms, udp_read_write_timeout_ms, log_level, limit_nofile),
                )| TunnelConfigPayload {
                    tunnel_name,
                    tunnel_mtu,
                    multi_queue,
                    tunnel_ipv4,
                    tunnel_ipv6,
                    socks5_address,
                    socks5_port,
                    socks5_udp,
                    socks5_udp_address,
                    socks5_pipeline,
                    username,
                    password,
                    mapdns_address,
                    mapdns_port,
                    mapdns_network,
                    mapdns_netmask,
                    mapdns_cache_size,
                    encrypted_dns_resolver_id: None,
                    encrypted_dns_protocol: None,
                    encrypted_dns_host: None,
                    encrypted_dns_port: None,
                    encrypted_dns_tls_server_name: None,
                    encrypted_dns_doh_url: None,
                    encrypted_dns_dnscrypt_provider_name: None,
                    encrypted_dns_dnscrypt_public_key: None,
                    doh_resolver_id: None,
                    doh_url: None,
                    doh_bootstrap_ips: Vec::new(),
                    encrypted_dns_bootstrap_ips: Vec::new(),
                    dns_query_timeout_ms: None,
                    resolver_fallback_active: None,
                    resolver_fallback_reason: None,
                    task_stack_size,
                    tcp_buffer_size,
                    udp_recv_buffer_size,
                    udp_copy_buffer_nums,
                    max_session_count,
                    connect_timeout_ms,
                    tcp_read_write_timeout_ms,
                    udp_read_write_timeout_ms,
                    log_level,
                    limit_nofile,
                    filter_injected_resets: None,
                },
            )
    }

    fn valid_tunnel_payload_strategy() -> impl Strategy<Value = TunnelConfigPayload> {
        (
            (
                non_blank_string(24),
                68u32..65_536,
                any::<bool>(),
                prop::option::of(ipv4_address()),
                prop::option::of(prop_oneof![Just("fd00::1".to_string()), Just("2001:db8::1".to_string())]),
                ipv4_address(),
                1u16..=u16::MAX,
                prop::option::of(non_blank_string(12)),
                prop::option::of(ipv4_address()),
                prop::option::of(any::<bool>()),
                prop::option::of(non_blank_string(16)),
                prop::option::of(non_blank_string(16)),
            ),
            (
                prop::option::of(ipv4_address()),
                prop::option::of(1u16..=u16::MAX),
                prop::option::of(Just("172.16.0.0".to_string())),
                prop::option::of(Just("255.240.0.0".to_string())),
                prop::option::of(1u32..50_001),
                8_192u32..262_145,
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..262_145),
                prop::option::of(1u32..1025),
                prop::option::of(1u32..100_001),
            ),
            // connect_timeout, tcp/udp rw timeouts, log_level, limit_nofile
            (
                prop::option::of(1u32..300_001),
                prop::option::of(1u32..300_001),
                prop::option::of(1u32..300_001),
                prop_oneof![
                    Just("trace".to_string()),
                    Just("debug".to_string()),
                    Just("info".to_string()),
                    Just("warn".to_string()),
                    Just("error".to_string()),
                ],
                prop::option::of(128u32..65_536),
            ),
        )
            .prop_map(
                |(
                    (
                        tunnel_name,
                        tunnel_mtu,
                        multi_queue,
                        tunnel_ipv4,
                        tunnel_ipv6,
                        socks5_address,
                        socks5_port,
                        socks5_udp,
                        socks5_udp_address,
                        socks5_pipeline,
                        username,
                        password,
                    ),
                    (
                        mapdns_address,
                        mapdns_port,
                        mapdns_network,
                        mapdns_netmask,
                        mapdns_cache_size,
                        task_stack_size,
                        tcp_buffer_size,
                        udp_recv_buffer_size,
                        udp_copy_buffer_nums,
                        max_session_count,
                    ),
                    (connect_timeout_ms, tcp_read_write_timeout_ms, udp_read_write_timeout_ms, log_level, limit_nofile),
                )| TunnelConfigPayload {
                    tunnel_name,
                    tunnel_mtu,
                    multi_queue,
                    tunnel_ipv4,
                    tunnel_ipv6,
                    socks5_address,
                    socks5_port,
                    socks5_udp,
                    socks5_udp_address,
                    socks5_pipeline,
                    username,
                    password,
                    mapdns_address,
                    mapdns_port,
                    mapdns_network,
                    mapdns_netmask,
                    mapdns_cache_size,
                    encrypted_dns_resolver_id: None,
                    encrypted_dns_protocol: None,
                    encrypted_dns_host: None,
                    encrypted_dns_port: None,
                    encrypted_dns_tls_server_name: None,
                    encrypted_dns_doh_url: None,
                    encrypted_dns_dnscrypt_provider_name: None,
                    encrypted_dns_dnscrypt_public_key: None,
                    doh_resolver_id: None,
                    doh_url: None,
                    doh_bootstrap_ips: Vec::new(),
                    encrypted_dns_bootstrap_ips: Vec::new(),
                    dns_query_timeout_ms: None,
                    resolver_fallback_active: None,
                    resolver_fallback_reason: None,
                    task_stack_size,
                    tcp_buffer_size,
                    udp_recv_buffer_size,
                    udp_copy_buffer_nums,
                    max_session_count,
                    connect_timeout_ms,
                    tcp_read_write_timeout_ms,
                    udp_read_write_timeout_ms,
                    log_level,
                    limit_nofile,
                    filter_injected_resets: None,
                },
            )
    }

    #[test]
    fn builds_config_from_json_payload() {
        let config = config_from_payload(sample_payload()).expect("config");
        assert_eq!(config.socks5.address, "127.0.0.1");
        assert_eq!(config.misc.task_stack_size, 81_920);
    }

    #[test]
    fn preserves_ipv4_and_ipv6_tunnel_addresses() {
        let mut payload = sample_payload();
        payload.tunnel_ipv4 = Some("10.10.10.10/32".to_string());
        payload.tunnel_ipv6 = Some("fd00::1/128".to_string());

        let config = config_from_payload(payload).expect("config");

        assert_eq!(config.tunnel.ipv4.as_deref(), Some("10.10.10.10/32"));
        assert_eq!(config.tunnel.ipv6.as_deref(), Some("fd00::1/128"));
    }

    #[test]
    fn rejects_blank_socks5_address() {
        let mut payload = sample_payload();
        payload.socks5_address = "   ".to_string();

        let err = config_from_payload(payload).expect_err("blank address");

        assert_eq!(err, "socks5Address must not be blank");
    }

    #[test]
    fn rejects_blank_tunnel_name() {
        let mut payload = sample_payload();
        payload.tunnel_name = "   ".to_string();

        let err = config_from_payload(payload).expect_err("blank tunnel name");

        assert_eq!(err, "tunnelName must not be blank");
    }

    #[test]
    fn rejects_invalid_tunnel_json_payload() {
        let err = parse_tunnel_config_json("{").expect_err("invalid json");

        assert!(err.contains("Invalid tunnel config JSON"));
    }

    #[test]
    fn rejects_zero_socks5_port() {
        let mut payload = sample_payload();
        payload.socks5_port = 0;
        let err = config_from_payload(payload).expect_err("zero port");
        assert_eq!(err, "socks5Port must be non-zero");
    }

    #[test]
    fn rejects_mtu_below_minimum() {
        let mut payload = sample_payload();
        payload.tunnel_mtu = 67;
        let err = config_from_payload(payload).expect_err("low mtu");
        assert!(err.contains("tunnelMtu must be between"));
    }

    #[test]
    fn rejects_mtu_above_maximum() {
        let mut payload = sample_payload();
        payload.tunnel_mtu = 65536;
        let err = config_from_payload(payload).expect_err("high mtu");
        assert!(err.contains("tunnelMtu must be between"));
    }

    #[test]
    fn rejects_task_stack_size_below_minimum() {
        let mut payload = sample_payload();
        payload.task_stack_size = 4_096;
        let err = config_from_payload(payload).expect_err("low stack");
        assert!(err.contains("taskStackSize must be between"));
    }

    #[test]
    fn rejects_zero_connect_timeout() {
        let mut payload = sample_payload();
        payload.connect_timeout_ms = Some(0);
        let err = config_from_payload(payload).expect_err("zero timeout");
        assert!(err.contains("connectTimeoutMs must be between"));
    }

    #[test]
    fn rejects_excessive_limit_nofile() {
        let mut payload = sample_payload();
        payload.limit_nofile = Some(2_000_000);
        let err = config_from_payload(payload).expect_err("high nofile");
        assert!(err.contains("limitNofile must be between"));
    }

    #[test]
    fn accepts_boundary_mtu_values() {
        let mut payload = sample_payload();
        payload.tunnel_mtu = 68;
        assert!(config_from_payload(payload).is_ok());

        let mut payload = sample_payload();
        payload.tunnel_mtu = 65535;
        assert!(config_from_payload(payload).is_ok());
    }

    #[test]
    fn accepts_kotlin_defaulted_tunnel_fields_when_omitted() {
        let payload = parse_tunnel_config_json(
            r#"{
                  "socks5Port": 1080,
                  "mapdnsAddress": "198.18.0.53",
                  "mapdnsPort": 53,
                  "mapdnsNetwork": "198.18.0.0",
                  "mapdnsNetmask": "255.254.0.0",
                  "encryptedDnsResolverId": "cloudflare",
                  "encryptedDnsProtocol": "doh",
                  "encryptedDnsHost": "cloudflare-dns.com",
                  "encryptedDnsPort": 443,
                  "encryptedDnsTlsServerName": "cloudflare-dns.com",
                  "encryptedDnsBootstrapIps": ["1.1.1.1", "1.0.0.1"],
                  "encryptedDnsDohUrl": "https://cloudflare-dns.com/dns-query"
                }"#,
        )
        .expect("payload");

        let config = config_from_payload(payload).expect("config");

        assert_eq!(config.tunnel.name, "tun0");
        assert_eq!(config.tunnel.mtu, 1500);
        assert!(!config.tunnel.multi_queue);
        assert_eq!(config.socks5.address, "127.0.0.1");
        assert_eq!(config.socks5.udp.as_deref(), Some("udp"));
        assert_eq!(config.misc.task_stack_size, 81_920);
        assert_eq!(config.misc.log_level, "warn");
    }
    proptest! {
        #[test]
        fn fuzz_tunnel_json_parser_never_panics(input in vec(any::<u8>(), 0..512)) {
            let payload = String::from_utf8_lossy(&input).into_owned();
            let _ = parse_tunnel_config_json(&payload);
        }

        #[test]
        fn fuzz_tunnel_payload_mapping_never_panics(payload in tunnel_payload_strategy()) {
            let _ = config_from_payload(payload);
        }

        #[test]
        fn valid_tunnel_payloads_preserve_core_fields(payload in valid_tunnel_payload_strategy()) {
            let expected_name = payload.tunnel_name.clone();
            let expected_mtu = payload.tunnel_mtu;
            let expected_multi_queue = payload.multi_queue;
            let expected_address = payload.socks5_address.clone();
            let expected_port = payload.socks5_port;
            let expected_pipeline = payload.socks5_pipeline;
            let expected_stack_size = payload.task_stack_size;
            let expected_log_level = payload.log_level.clone();

            let config = config_from_payload(payload).expect("valid tunnel payload");

            assert_eq!(config.tunnel.name, expected_name);
            assert_eq!(config.tunnel.mtu, expected_mtu);
            assert_eq!(config.tunnel.multi_queue, expected_multi_queue);
            assert_eq!(config.socks5.address, expected_address);
            assert_eq!(config.socks5.port, expected_port);
            assert_eq!(config.socks5.pipeline, expected_pipeline);
            assert_eq!(config.misc.task_stack_size, expected_stack_size);
            assert_eq!(config.misc.log_level, expected_log_level);
        }
    }

    #[test]
    #[ignore = "startup latency smoke"]
    fn startup_latency_smoke() {
        use std::time::{Duration, Instant};

        let start = Instant::now();
        let _ = config_from_payload(sample_payload()).expect("config");
        assert!(start.elapsed() < Duration::from_millis(50), "tunnel config startup path regressed");
    }
}
