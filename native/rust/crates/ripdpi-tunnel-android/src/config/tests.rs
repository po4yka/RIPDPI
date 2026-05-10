use proptest::collection::vec;
use proptest::prelude::*;

use super::*;

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
                encrypted_dns_bootstrap_ips: Vec::new(),
                dns_query_timeout_ms: None,
                resolver_fallback_active: None,
                resolver_fallback_reason: None,
                strategy_chain_yaml: None,
                protect_path: None,
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
                log_context: None,
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
                encrypted_dns_bootstrap_ips: Vec::new(),
                dns_query_timeout_ms: None,
                resolver_fallback_active: None,
                resolver_fallback_reason: None,
                strategy_chain_yaml: None,
                protect_path: None,
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
                log_context: None,
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
fn maps_synack_runtime_fields_to_misc_config() {
    let chain_yaml = "version: 1\nchains:\n  - id: vpn-synack\n    steps:\n      - type: synack\n";
    let mut payload = sample_payload();
    payload.strategy_chain_yaml = Some(chain_yaml.to_string());
    payload.protect_path = Some("/tmp/ripdpi-protect.sock".to_string());

    let config = config_from_payload(payload).expect("config");

    assert_eq!(config.misc.strategy_chain_yaml.as_deref(), Some(chain_yaml));
    assert_eq!(config.misc.protect_path.as_deref(), Some("/tmp/ripdpi-protect.sock"),);
}

#[test]
fn drops_blank_synack_runtime_fields() {
    let mut payload = sample_payload();
    payload.strategy_chain_yaml = Some(" \n\t ".to_string());
    payload.protect_path = Some("  ".to_string());

    let config = config_from_payload(payload).expect("config");

    assert_eq!(config.misc.strategy_chain_yaml, None);
    assert_eq!(config.misc.protect_path, None);
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

#[test]
fn tunnel_config_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let payload_json = r#"{
        "tunnelName": "tun0",
        "tunnelMtu": 1500,
        "multiQueue": false,
        "tunnelIpv4": "10.0.0.2",
        "tunnelIpv6": "fd00::2",
        "socks5Address": "127.0.0.1",
        "socks5Port": 1080,
        "socks5Udp": "udp-relay",
        "socks5UdpAddress": "127.0.0.2",
        "socks5Pipeline": true,
        "username": "user",
        "password": "secret",
        "mapdnsAddress": "10.0.0.53",
        "mapdnsPort": 5353,
        "mapdnsNetwork": "10.0.0.0",
        "mapdnsNetmask": "255.255.255.0",
        "mapdnsCacheSize": 4096,
        "encryptedDnsResolverId": "cloudflare",
        "encryptedDnsProtocol": "doh",
        "encryptedDnsHost": "cloudflare-dns.com",
        "encryptedDnsPort": 443,
        "encryptedDnsTlsServerName": "cloudflare-dns.com",
        "encryptedDnsDohUrl": "https://cloudflare-dns.com/dns-query",
        "encryptedDnsDnscryptProviderName": "provider",
        "encryptedDnsDnscryptPublicKey": "key",
        "encryptedDnsBootstrapIps": ["1.0.0.1"],
        "dnsQueryTimeoutMs": 4000,
        "resolverFallbackActive": true,
        "resolverFallbackReason": "timeout",
        "strategyChainYaml": "version: 1\nchains:\n  - id: vpn-synack",
        "protectPath": "/data/user/0/com.poyka.ripdpi/files/protect_path",
        "taskStackSize": 81920,
        "tcpBufferSize": 32768,
        "udpRecvBufferSize": 16384,
        "udpCopyBufferNums": 8,
        "maxSessionCount": 2048,
        "connectTimeoutMs": 3000,
        "tcpReadWriteTimeoutMs": 6000,
        "udpReadWriteTimeoutMs": 7000,
        "logLevel": "info",
        "limitNofile": 4096,
        "filterInjectedResets": true,
        "logContext": {
            "runtimeId": "rt-1",
            "mode": "auto",
            "policySignature": "sig",
            "fingerprintHash": "hash",
            "diagnosticsSessionId": "diag-1"
        }
    }"#;

    let payload: serde_json::Value = serde_json::from_str(payload_json).expect("parse JSON");
    let _: TunnelConfigPayload = serde_json::from_value(payload.clone()).expect("deserialize tunnel config");

    let paths = extract_field_paths(&payload);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("tunnel_config_fields.json", &manifest);
}
