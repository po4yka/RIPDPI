#[cfg(test)]
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::{
    parse_proxy_config_json as shared_parse_proxy_config_json,
    runtime_config_envelope_from_payload as shared_runtime_config_envelope_from_payload, ProxyConfigError,
    ProxyConfigPayload, RuntimeConfigEnvelope,
};
#[cfg(test)]
use ripdpi_proxy_config::{
    runtime_config_from_command_line as shared_runtime_config_from_command_line,
    runtime_config_from_payload as shared_runtime_config_from_payload,
    runtime_config_from_ui as shared_runtime_config_from_ui, ProxyUiConfig,
};

use crate::errors::JniProxyError;

#[cfg(test)]
pub(crate) const HOSTS_DISABLE: &str = "disable";
#[cfg(test)]
pub(crate) const HOSTS_BLACKLIST: &str = "blacklist";
#[cfg(test)]
pub(crate) const HOSTS_WHITELIST: &str = "whitelist";

pub(crate) fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, JniProxyError> {
    shared_runtime_config_envelope_from_payload(payload).map_err(proxy_config_error)
}

#[cfg(test)]
pub(crate) fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_payload(payload).map_err(proxy_config_error)
}

#[cfg(test)]
pub(crate) fn runtime_config_from_command_line(args: Vec<String>) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_command_line(args).map_err(proxy_config_error)
}

#[cfg(test)]
pub(crate) fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_ui(payload).map_err(proxy_config_error)
}

pub(crate) fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, JniProxyError> {
    shared_parse_proxy_config_json(json).map_err(proxy_config_error)
}

fn proxy_config_error(err: ProxyConfigError) -> JniProxyError {
    JniProxyError::InvalidConfig(err.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::IpAddr;
    use std::str::FromStr;

    use proptest::collection::vec;
    use proptest::prelude::*;
    use ripdpi_config::{
        QuicFakeProfile, QuicInitialMode, TcpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
    };
    use ripdpi_proxy_config::{
        ProxyUiActivationFilter, ProxyUiChainConfig, ProxyUiFakePacketConfig, ProxyUiHostAutolearnConfig,
        ProxyUiHostsConfig, ProxyUiListenConfig, ProxyUiParserEvasionConfig, ProxyUiProtocolConfig, ProxyUiQuicConfig,
        ProxyUiTcpChainStep, ProxyUiUdpChainStep, FAKE_TLS_SNI_MODE_RANDOMIZED, QUIC_FAKE_PROFILE_DISABLED,
    };

    fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
        vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
    }

    fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
        ProxyUiTcpChainStep {
            kind: kind.to_string(),
            marker: marker.to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }
    }

    fn udp_step(kind: &str, count: i32) -> ProxyUiUdpChainStep {
        ProxyUiUdpChainStep {
            kind: kind.to_string(),
            count,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }
    }

    fn minimal_ui_config() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
        config
    }

    fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
        ProxyConfigPayload::Ui { strategy_preset: None, config, runtime_context: None, log_context: None }
    }

    fn command_line_payload(args: Vec<String>) -> ProxyConfigPayload {
        ProxyConfigPayload::CommandLine {
            args,
            host_autolearn_store_path: None,
            runtime_context: None,
            log_context: None,
        }
    }

    fn proxy_ui_config_strategy() -> impl Strategy<Value = ProxyUiConfig> {
        let listen = (
            lossy_string(48),
            -32i32..65_536i32,
            -16i32..4_096i32,
            -16i32..65_536i32,
            any::<bool>(),
            -16i32..512i32,
            any::<bool>(),
        );
        let protocols = (any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>());
        let tcp = (
            prop_oneof![
                Just("none".to_string()),
                Just("split".to_string()),
                Just("disorder".to_string()),
                Just("fake".to_string()),
                Just("oob".to_string()),
                Just("disoob".to_string()),
                lossy_string(16),
            ],
            proptest::option::of(lossy_string(24)),
            any::<bool>(),
            0i32..8i32,
        );
        let fake_packets = (
            -16i32..512i32,
            lossy_string(64),
            any::<u8>(),
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            -16i32..1_024i32,
            proptest::option::of(lossy_string(24)),
            any::<bool>(),
        );
        let parser_evasions = (any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>());
        let hosts = (
            prop_oneof![
                Just(HOSTS_DISABLE.to_string()),
                Just(HOSTS_BLACKLIST.to_string()),
                Just(HOSTS_WHITELIST.to_string()),
                lossy_string(16),
            ],
            proptest::option::of(lossy_string(64)),
        );
        let quic = (
            prop_oneof![
                Just("disabled".to_string()),
                Just("route".to_string()),
                Just("route_and_cache".to_string()),
                lossy_string(24),
            ],
            any::<bool>(),
            any::<bool>(),
            prop_oneof![
                Just(QUIC_FAKE_PROFILE_DISABLED.to_string()),
                Just("compat_default".to_string()),
                Just("realistic_initial".to_string()),
                lossy_string(24),
            ],
            lossy_string(64),
        );
        let autolearn = (any::<bool>(), -32i64..48i64, 0usize..2_048usize, proptest::option::of(lossy_string(96)));
        let udp_fake_count = 0i32..8i32;

        (listen, protocols, tcp, fake_packets, parser_evasions, hosts, quic, autolearn, udp_fake_count).prop_map(
            |(
                (ip, port, max_connections, buffer_size, tcp_fast_open, default_ttl, custom_ttl),
                (resolve_domains, desync_http, desync_https, desync_udp),
                (desync_method, split_marker, use_activation_filter, fragment_count),
                (
                    fake_ttl,
                    fake_sni,
                    oob_char,
                    fake_tls_use_original,
                    fake_tls_randomize,
                    fake_tls_dup_session_id,
                    fake_tls_pad_encap,
                    fake_tls_size,
                    fake_offset_marker,
                    drop_sack,
                ),
                (host_mixed_case, domain_mixed_case, host_remove_spaces, http_method_eol, http_unix_eol),
                (hosts_mode, hosts_entries),
                (quic_initial_mode, quic_support_v1, quic_support_v2, quic_fake_profile, quic_fake_host),
                (
                    host_autolearn_enabled,
                    host_autolearn_penalty_ttl_hours,
                    host_autolearn_max_hosts,
                    host_autolearn_store_path,
                ),
                udp_fake_count,
            )| {
                let mut config = minimal_ui_config();
                config.listen = ProxyUiListenConfig {
                    ip,
                    port,
                    max_connections,
                    buffer_size,
                    tcp_fast_open,
                    default_ttl,
                    custom_ttl,
                    freeze_detection_enabled: false,
                    auth_token: None,
                };
                config.protocols = ProxyUiProtocolConfig { resolve_domains, desync_http, desync_https, desync_udp };
                config.chains = ProxyUiChainConfig {
                    tcp_steps: if desync_method == "none" {
                        Vec::new()
                    } else {
                        vec![ProxyUiTcpChainStep {
                            kind: desync_method,
                            marker: split_marker
                                .filter(|value| !value.trim().is_empty())
                                .unwrap_or_else(|| "1".to_string()),
                            midhost_marker: String::new(),
                            fake_host_template: String::new(),
                            overlap_size: 0,
                            fake_mode: String::new(),
                            fragment_count,
                            min_fragment_size: 0,
                            max_fragment_size: 0,
                            inter_segment_delay_ms: 0,
                            activation_filter: use_activation_filter.then_some(ProxyUiActivationFilter::default()),
                            ipv6_extension_profile: "none".to_string(),
                        }]
                    },
                    udp_steps: if udp_fake_count > 0 {
                        vec![udp_step("fake_burst", udp_fake_count)]
                    } else {
                        Vec::new()
                    },
                    group_activation_filter: None,
                };
                config.fake_packets = ProxyUiFakePacketConfig {
                    fake_ttl,
                    fake_sni,
                    oob_char,
                    fake_tls_use_original,
                    fake_tls_randomize,
                    fake_tls_dup_session_id,
                    fake_tls_pad_encap,
                    fake_tls_size,
                    fake_offset_marker: fake_offset_marker.unwrap_or_else(|| "0".to_string()),
                    drop_sack,
                    ..config.fake_packets
                };
                config.parser_evasions = ProxyUiParserEvasionConfig {
                    host_mixed_case,
                    domain_mixed_case,
                    host_remove_spaces,
                    http_method_eol,
                    http_unix_eol,
                    http_method_space: false,
                    http_host_pad: false,
                    http_host_extra_space: false,
                    http_host_tab: false,
                };
                config.hosts = ProxyUiHostsConfig { mode: hosts_mode, entries: hosts_entries };
                config.quic = ProxyUiQuicConfig {
                    initial_mode: quic_initial_mode,
                    support_v1: quic_support_v1,
                    support_v2: quic_support_v2,
                    fake_profile: quic_fake_profile,
                    fake_host: quic_fake_host,
                };
                config.host_autolearn = ProxyUiHostAutolearnConfig {
                    enabled: host_autolearn_enabled,
                    penalty_ttl_hours: host_autolearn_penalty_ttl_hours,
                    max_hosts: host_autolearn_max_hosts,
                    store_path: host_autolearn_store_path,
                    network_scope_key: None,
                    warmup_probe_enabled: true,
                    network_reprobe_enabled: true,
                };
                config
            },
        )
    }

    #[test]
    fn parses_ui_config_payload() {
        let mut ui = minimal_ui_config();
        ui.protocols.desync_udp = false;
        ui.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
        ui.fake_packets.fake_ttl = 8;
        ui.fake_packets.fake_sni = "www.iana.org".to_string();
        ui.fake_packets.fake_tls_use_original = true;
        ui.fake_packets.fake_tls_randomize = true;
        ui.fake_packets.fake_tls_dup_session_id = true;
        ui.fake_packets.fake_tls_pad_encap = true;
        ui.fake_packets.fake_tls_size = 192;
        ui.fake_packets.fake_tls_sni_mode = FAKE_TLS_SNI_MODE_RANDOMIZED.to_string();
        ui.quic.initial_mode = "route".to_string();
        ui.quic.support_v1 = false;
        ui.quic.support_v2 = true;
        ui.quic.fake_profile = "realistic_initial".to_string();
        ui.quic.fake_host = "video.example.test".to_string();
        ui.host_autolearn.enabled = true;
        ui.host_autolearn.penalty_ttl_hours = 1;
        ui.host_autolearn.max_hosts = 128;
        ui.host_autolearn.store_path = Some("/tmp/host-autolearn-v2.json".to_string());
        ui.host_autolearn.network_scope_key = Some("scope-a".to_string());

        let config = runtime_config_from_payload(ui_payload(ui)).expect("ui config");

        assert_eq!(config.network.listen.listen_port, 1080);
        // TCP primary + adaptive_direct + 3 ripdpi_default fallback groups + CONNECT passthrough
        assert_eq!(config.groups.len(), 6);
        assert_eq!(config.quic.initial_mode, QuicInitialMode::Route);
        assert!(!config.quic.support_v1);
        assert!(config.quic.support_v2);
        assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].actions.quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(config.groups[0].actions.fake_mod, FM_ORIG | FM_RAND | FM_DUPSID | FM_PADENCAP | FM_RNDSNI);
        assert_eq!(config.groups[0].actions.fake_tls_size, 192);
        assert!(config.groups[0].actions.fake_sni_list.is_empty());
        assert!(config.host_autolearn.enabled);
        assert_eq!(config.host_autolearn.penalty_ttl_secs, 3_600);
        assert_eq!(config.host_autolearn.max_hosts, 128);
        assert_eq!(config.host_autolearn.store_path.as_deref(), Some("/tmp/host-autolearn-v2.json"));
    }

    #[test]
    fn parses_hostfake_tcp_chain_step_payload() {
        let mut ui = minimal_ui_config();
        ui.chains.tcp_steps = vec![ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: "midsld".to_string(),
            fake_host_template: "googlevideo.com".to_string(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: Some(ProxyUiActivationFilter::default()),
            ipv6_extension_profile: "none".to_string(),
        }];

        let config = runtime_config_from_payload(ui_payload(ui)).expect("hostfake ui config");
        let group = &config.groups[0];

        assert_eq!(group.actions.tcp_chain.len(), 2);
        assert!(matches!(group.actions.tcp_chain[0].kind, TcpChainStepKind::TlsRec));
        assert_eq!(
            group.actions.tcp_chain[0].offset,
            ripdpi_config::OffsetExpr::tls_marker(ripdpi_config::OffsetBase::ExtLen, 0)
        );
        assert!(matches!(group.actions.tcp_chain[1].kind, TcpChainStepKind::HostFake));
        assert_eq!(
            group.actions.tcp_chain[1].midhost_offset,
            Some(ripdpi_config::OffsetExpr::marker(ripdpi_config::OffsetBase::MidSld, 0))
        );
        assert_eq!(group.actions.tcp_chain[1].fake_host_template.as_deref(), Some("googlevideo.com"));
    }

    #[test]
    fn parses_tlsrandrec_tcp_chain_step_payload() {
        let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
        value["chains"]["tcpSteps"] = serde_json::json!([
            {
                "kind": "tlsrandrec",
                "marker": "sniext+4",
                "midhostMarker": "",
                "fakeHostTemplate": "",
                "fragmentCount": 5,
                "minFragmentSize": 24,
                "maxFragmentSize": 48
            }
        ]);

        let payload = parse_proxy_config_json(&value.to_string()).expect("parse tlsrandrec payload");
        let config = runtime_config_from_payload(payload).expect("tlsrandrec ui config");
        let step = &config.groups[0].actions.tcp_chain[0];

        assert!(matches!(step.kind, TcpChainStepKind::TlsRandRec));
        assert_eq!(step.fragment_count, 5);
        assert_eq!(step.min_fragment_size, 24);
        assert_eq!(step.max_fragment_size, 48);
    }

    #[test]
    fn rejects_tlsrandrec_fragment_fields_on_non_tlsrandrec_steps() {
        let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
        value["chains"]["tcpSteps"] = serde_json::json!([
            {
                "kind": "split",
                "marker": "host+1",
                "midhostMarker": "",
                "fakeHostTemplate": "",
                "fragmentCount": 5,
                "minFragmentSize": 0,
                "maxFragmentSize": 0
            }
        ]);

        let payload = parse_proxy_config_json(&value.to_string()).expect("parse invalid payload");
        let err = runtime_config_from_payload(payload).expect_err("non-tlsrandrec fragment fields should fail");

        assert!(err.to_string().contains("tlsrandrec fragment fields are only supported"));
    }

    #[test]
    fn parses_command_line_payloads_for_runtime_config() {
        let config = runtime_config_from_payload(command_line_payload(vec![
            "ripdpi".to_string(),
            "--ip".to_string(),
            "127.0.0.1".to_string(),
            "--port".to_string(),
            "2080".to_string(),
            "--split".to_string(),
            "host+1".to_string(),
        ]))
        .expect("command-line config");

        assert_eq!(config.network.listen.listen_ip, IpAddr::from_str("127.0.0.1").unwrap());
        assert_eq!(config.network.listen.listen_port, 2080);
    }

    #[test]
    fn rejects_non_runnable_command_line_payloads() {
        let err = runtime_config_from_command_line(vec!["ripdpi".to_string(), "--help".to_string()])
            .expect_err("help payload should not run");

        assert!(err.to_string().contains("runnable config"));
    }

    #[test]
    fn rejects_invalid_ui_proxy_port() {
        let mut ui = minimal_ui_config();
        ui.listen.port = 0;

        let err = runtime_config_from_payload(ui_payload(ui)).expect_err("port zero should be rejected");

        assert!(err.to_string().contains("Invalid proxy port"));
    }

    #[test]
    fn ui_payload_defaults_quic_settings_when_omitted() {
        let mut value = serde_json::to_value(ui_payload(minimal_ui_config())).expect("serialize payload");
        value.as_object_mut().expect("payload object").remove("quic");

        let payload = parse_proxy_config_json(&value.to_string()).expect("parse ui payload");
        let config = runtime_config_from_payload(payload).expect("ui config");

        assert_eq!(config.quic.initial_mode, QuicInitialMode::RouteAndCache);
        assert!(config.quic.support_v1);
        assert!(config.quic.support_v2);
        assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::Disabled);
        assert_eq!(config.groups[0].actions.quic_fake_host, None);
    }

    #[test]
    fn rejects_unknown_quic_initial_mode_in_ui_payload() {
        let mut ui = minimal_ui_config();
        ui.quic.initial_mode = "bogus".to_string();

        let err = runtime_config_from_payload(ui_payload(ui)).expect_err("unknown quic mode should be rejected");

        assert!(err.to_string().contains("Unknown quicInitialMode"));
    }

    #[test]
    fn rejects_unknown_quic_fake_profile_in_ui_payload() {
        let mut ui = minimal_ui_config();
        ui.quic.fake_profile = "bogus".to_string();

        let err =
            runtime_config_from_payload(ui_payload(ui)).expect_err("unknown quic fake profile should be rejected");

        assert!(err.to_string().contains("Unknown quicFakeProfile"));
    }

    #[test]
    fn invalid_quic_fake_host_normalizes_to_absent() {
        let mut ui = minimal_ui_config();
        ui.quic.fake_profile = "realistic_initial".to_string();
        ui.quic.fake_host = "127.0.0.1".to_string();

        let config = runtime_config_from_payload(ui_payload(ui)).expect("ui config");

        assert_eq!(config.groups[0].actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].actions.quic_fake_host, None);
    }

    #[test]
    fn rejects_invalid_proxy_json_payload() {
        let err = parse_proxy_config_json("{").expect_err("invalid json");

        assert!(err.to_string().contains("Invalid proxy config JSON"));
    }

    #[test]
    fn rejects_legacy_flat_ui_payload() {
        let err = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "desyncMethod": "disorder",
            })
            .to_string(),
        )
        .expect_err("legacy flat ui payload should be rejected");

        assert!(err.to_string().contains("Legacy flat UI config JSON is not supported"));
    }

    #[test]
    fn rejects_enabled_autolearn_without_store_path() {
        let mut ui = minimal_ui_config();
        ui.host_autolearn.enabled = true;
        ui.host_autolearn.store_path = None;

        let err = runtime_config_from_payload(ui_payload(ui)).expect_err("missing autolearn path should be rejected");

        assert!(err.to_string().contains("hostAutolearn.storePath is required when hostAutolearn.enabled is true"));
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(256))]

        #[test]
        fn fuzz_proxy_json_parser_never_panics(input in lossy_string(512)) {
            let _ = parse_proxy_config_json(&input);
        }

        #[test]
        fn fuzz_command_line_parser_never_panics(args in vec(lossy_string(32), 0..12)) {
            let _ = runtime_config_from_command_line(args);
        }

        #[test]
        fn fuzz_ui_payload_mapping_never_panics(payload in proxy_ui_config_strategy()) {
            let _ = runtime_config_from_ui(payload);
        }

        #[test]
        fn valid_ui_payloads_preserve_core_fields(
            ip in prop_oneof![
                Just("127.0.0.1".to_string()),
                Just("0.0.0.0".to_string()),
                Just("::1".to_string()),
            ],
            port in 1i32..65_536i32,
            max_connections in 1i32..4_096i32,
            buffer_size in 1i32..65_536i32,
            tcp_fast_open in any::<bool>(),
            drop_sack in any::<bool>(),
            udp_fake_count in 0i32..8i32,
            hosts_mode in prop_oneof![
                Just(HOSTS_DISABLE.to_string()),
                Just(HOSTS_BLACKLIST.to_string()),
                Just(HOSTS_WHITELIST.to_string()),
            ],
        ) {
            let hosts = match hosts_mode.as_str() {
                HOSTS_DISABLE => None,
                _ => Some("example.org".to_string()),
            };

            let mut ui = minimal_ui_config();
            ui.listen = ProxyUiListenConfig {
                ip: ip.clone(),
                port,
                max_connections,
                buffer_size,
                tcp_fast_open,
                default_ttl: 64,
                custom_ttl: true,
                freeze_detection_enabled: false,
                auth_token: None,
            };
            ui.fake_packets.drop_sack = drop_sack;
            ui.hosts.mode = hosts_mode;
            ui.hosts.entries = hosts;
            ui.chains.udp_steps = if udp_fake_count > 0 { vec![udp_step("fake_burst", udp_fake_count)] } else { Vec::new() };

            let config = runtime_config_from_ui(ui).expect("valid payload");

            prop_assert_eq!(config.network.listen.listen_ip, IpAddr::from_str(&ip).expect("valid ip"));
            prop_assert_eq!(config.network.listen.listen_port, u16::try_from(port).expect("valid port"));
            prop_assert_eq!(config.network.max_open, max_connections);
            prop_assert_eq!(config.network.buffer_size, usize::try_from(buffer_size).expect("valid buffer size"));
            prop_assert_eq!(config.network.tfo, tcp_fast_open);
            prop_assert!(!config.groups.is_empty());
        }
    }
}
