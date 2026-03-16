use ciadpi_config::RuntimeConfig;
use ripdpi_proxy_config::{
    parse_proxy_config_json as shared_parse_proxy_config_json,
    runtime_config_envelope_from_payload as shared_runtime_config_envelope_from_payload,
    runtime_config_from_command_line as shared_runtime_config_from_command_line,
    runtime_config_from_payload as shared_runtime_config_from_payload,
    runtime_config_from_ui as shared_runtime_config_from_ui, ProxyConfigError, ProxyConfigPayload, ProxyUiConfig,
    RuntimeConfigEnvelope, FAKE_TLS_SNI_MODE_FIXED,
};

use crate::errors::JniProxyError;

pub(crate) const HOSTS_DISABLE: &str = "disable";
pub(crate) const HOSTS_BLACKLIST: &str = "blacklist";
pub(crate) const HOSTS_WHITELIST: &str = "whitelist";

pub(crate) fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

pub(crate) fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, JniProxyError> {
    shared_runtime_config_envelope_from_payload(payload).map_err(proxy_config_error)
}

pub(crate) fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_payload(payload).map_err(proxy_config_error)
}

pub(crate) fn runtime_config_from_command_line(args: Vec<String>) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_command_line(args).map_err(proxy_config_error)
}

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

    use ciadpi_config::{
        QuicFakeProfile, QuicInitialMode, TcpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
        HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
    };
    use proptest::collection::vec;
    use proptest::prelude::*;
    use ripdpi_proxy_config::{
        ProxyUiActivationFilter, ProxyUiTcpChainStep, FAKE_TLS_SNI_MODE_RANDOMIZED, QUIC_FAKE_PROFILE_DISABLED,
    };

    fn lossy_string(max_len: usize) -> impl Strategy<Value = String> {
        vec(any::<u8>(), 0..max_len).prop_map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
    }

    fn ui_payload(config: ProxyUiConfig) -> ProxyConfigPayload {
        ProxyConfigPayload::Ui { config, runtime_context: None }
    }

    fn command_line_payload(args: Vec<String>) -> ProxyConfigPayload {
        ProxyConfigPayload::CommandLine { args, runtime_context: None }
    }

    fn proxy_ui_config_strategy() -> impl Strategy<Value = ProxyUiConfig> {
        let core = (lossy_string(48), -32i32..65_536i32, -16i32..4_096i32, -16i32..65_536i32, -16i32..512i32);
        let toggles = (any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>(), any::<bool>());
        let desync = (
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
            -64i32..64i32,
            any::<bool>(),
            -16i32..512i32,
            lossy_string(64),
            any::<u8>(),
        );
        let mutations = (
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            any::<bool>(),
            proptest::option::of(lossy_string(24)),
            -64i32..64i32,
            any::<bool>(),
        );
        let hosts = (
            prop_oneof![
                Just(HOSTS_DISABLE.to_string()),
                Just(HOSTS_BLACKLIST.to_string()),
                Just(HOSTS_WHITELIST.to_string()),
                lossy_string(16),
            ],
            proptest::option::of(lossy_string(64)),
            any::<bool>(),
            -8i32..16i32,
            any::<bool>(),
            proptest::option::of(lossy_string(24)),
            -64i32..64i32,
        );
        let quic = (
            prop_oneof![
                Just(Some("disabled".to_string())),
                Just(Some("route".to_string())),
                Just(Some("route_and_cache".to_string())),
                Just(None),
                proptest::option::of(lossy_string(24)),
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
        let autolearn = (
            any::<bool>(),
            -32i64..(HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS * 4),
            0usize..2_048usize,
            proptest::option::of(lossy_string(96)),
        );

        (core, toggles, desync, mutations, hosts, quic, autolearn).prop_map(
            |(
                (ip, port, max_connections, buffer_size, default_ttl),
                (custom_ttl, no_domain, desync_http, desync_https, desync_udp),
                (desync_method, split_marker, split_position, split_at_host, fake_ttl, fake_sni, oob_char),
                (
                    host_mixed_case,
                    domain_mixed_case,
                    host_remove_spaces,
                    tls_record_split,
                    tls_record_split_marker,
                    tls_record_split_position,
                    tls_record_split_at_sni,
                ),
                (hosts_mode, hosts, tcp_fast_open, udp_fake_count, drop_sack, fake_offset_marker, fake_offset),
                (quic_initial_mode, quic_support_v1, quic_support_v2, quic_fake_profile, quic_fake_host),
                (
                    host_autolearn_enabled,
                    host_autolearn_penalty_ttl_secs,
                    host_autolearn_max_hosts,
                    host_autolearn_store_path,
                ),
            )| ProxyUiConfig {
                ip,
                port,
                max_connections,
                buffer_size,
                default_ttl,
                custom_ttl,
                no_domain,
                desync_http,
                desync_https,
                desync_udp,
                desync_method,
                split_marker,
                tcp_chain_steps: Vec::new(),
                group_activation_filter: ProxyUiActivationFilter::default(),
                split_position,
                split_at_host,
                fake_ttl,
                adaptive_fake_ttl_enabled: false,
                adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
                adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
                adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
                adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
                fake_sni,
                http_fake_profile: "compat_default".to_string(),
                fake_tls_use_original: false,
                fake_tls_randomize: false,
                fake_tls_dup_session_id: false,
                fake_tls_pad_encap: false,
                fake_tls_size: 0,
                fake_tls_sni_mode: default_fake_tls_sni_mode(),
                tls_fake_profile: "compat_default".to_string(),
                oob_char,
                host_mixed_case,
                domain_mixed_case,
                host_remove_spaces,
                http_method_eol: false,
                http_unix_eol: false,
                tls_record_split,
                tls_record_split_marker,
                tls_record_split_position,
                tls_record_split_at_sni,
                hosts_mode,
                hosts,
                tcp_fast_open,
                udp_fake_count,
                udp_chain_steps: Vec::new(),
                udp_fake_profile: "compat_default".to_string(),
                drop_sack,
                fake_offset_marker,
                fake_offset,
                quic_initial_mode,
                quic_support_v1,
                quic_support_v2,
                quic_fake_profile,
                quic_fake_host,
                host_autolearn_enabled,
                host_autolearn_penalty_ttl_secs,
                host_autolearn_max_hosts,
                host_autolearn_store_path,
                network_scope_key: None,
                strategy_preset: None,
            },
        )
    }

    #[test]
    fn parses_ui_config_payload() {
        let payload = ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "fake".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: true,
            fake_tls_randomize: true,
            fake_tls_dup_session_id: true,
            fake_tls_pad_encap: true,
            fake_tls_size: 192,
            fake_tls_sni_mode: FAKE_TLS_SNI_MODE_RANDOMIZED.to_string(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route".to_string()),
            quic_support_v1: false,
            quic_support_v2: true,
            quic_fake_profile: "realistic_initial".to_string(),
            quic_fake_host: "video.example.test".to_string(),
            host_autolearn_enabled: true,
            host_autolearn_penalty_ttl_secs: 3_600,
            host_autolearn_max_hosts: 128,
            host_autolearn_store_path: Some("/tmp/host-autolearn-v1.json".to_string()),
            network_scope_key: Some("scope-a".to_string()),
            strategy_preset: None,
        });

        let config = runtime_config_from_payload(payload).expect("ui config");
        assert_eq!(config.listen.listen_port, 1080);
        assert_eq!(config.groups.len(), 2);
        assert_eq!(config.quic_initial_mode, QuicInitialMode::Route);
        assert!(!config.quic_support_v1);
        assert!(config.quic_support_v2);
        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(config.groups[0].fake_mod, FM_ORIG | FM_RAND | FM_DUPSID | FM_PADENCAP | FM_RNDSNI);
        assert_eq!(config.groups[0].fake_tls_size, 192);
        assert!(config.groups[0].fake_sni_list.is_empty());
        assert!(config.host_autolearn_enabled);
        assert_eq!(config.host_autolearn_penalty_ttl_secs, 3_600);
        assert_eq!(config.host_autolearn_max_hosts, 128);
        assert_eq!(config.host_autolearn_store_path.as_deref(), Some("/tmp/host-autolearn-v1.json"));
    }

    #[test]
    fn parses_hostfake_tcp_chain_step_payload() {
        let payload = ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: Some("1".to_string()),
            tcp_chain_steps: vec![ProxyUiTcpChainStep {
                kind: "hostfake".to_string(),
                marker: "endhost+8".to_string(),
                midhost_marker: Some("midsld".to_string()),
                fake_host_template: Some("googlevideo.com".to_string()),
                fragment_count: 0,
                min_fragment_size: 0,
                max_fragment_size: 0,
                activation_filter: ProxyUiActivationFilter::default(),
            }],
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        });

        let config = runtime_config_from_payload(payload).expect("hostfake ui config");
        let group = &config.groups[0];

        assert_eq!(group.tcp_chain.len(), 1);
        assert!(matches!(group.tcp_chain[0].kind, TcpChainStepKind::HostFake));
        assert_eq!(
            group.tcp_chain[0].midhost_offset,
            Some(ciadpi_config::OffsetExpr::marker(ciadpi_config::OffsetBase::MidSld, 0))
        );
        assert_eq!(group.tcp_chain[0].fake_host_template.as_deref(), Some("googlevideo.com"));
    }

    #[test]
    fn parses_tlsrandrec_tcp_chain_step_payload() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "1",
                "tcpChainSteps": [
                    {
                        "kind": "tlsrandrec",
                        "marker": "sniext+4",
                        "fragmentCount": 5,
                        "minFragmentSize": 24,
                        "maxFragmentSize": 48
                    }
                ],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0,
                "quicInitialMode": "route_and_cache",
                "quicSupportV1": true,
                "quicSupportV2": true,
                "quicFakeProfile": "disabled",
                "quicFakeHost": ""
            })
            .to_string(),
        )
        .expect("parse tlsrandrec payload");

        let config = runtime_config_from_payload(payload).expect("tlsrandrec ui config");
        let step = &config.groups[0].tcp_chain[0];

        assert!(matches!(step.kind, TcpChainStepKind::TlsRandRec));
        assert_eq!(step.fragment_count, 5);
        assert_eq!(step.min_fragment_size, 24);
        assert_eq!(step.max_fragment_size, 48);
    }

    #[test]
    fn rejects_tlsrandrec_fragment_fields_on_non_tlsrandrec_steps() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "1",
                "tcpChainSteps": [
                    {
                        "kind": "split",
                        "marker": "host+1",
                        "fragmentCount": 5
                    }
                ],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0,
                "quicInitialMode": "route_and_cache",
                "quicSupportV1": true,
                "quicSupportV2": true,
                "quicFakeProfile": "disabled",
                "quicFakeHost": ""
            })
            .to_string(),
        )
        .expect("parse invalid payload");

        let err = runtime_config_from_payload(payload).expect_err("non-tlsrandrec fragment fields should fail");

        assert!(err.to_string().contains("tlsrandrec fragment fields are only supported"));
    }

    #[test]
    fn parses_command_line_payloads_for_runtime_config() {
        let config = runtime_config_from_payload(command_line_payload(vec![
            "ciadpi".to_string(),
            "--ip".to_string(),
            "127.0.0.1".to_string(),
            "--port".to_string(),
            "2080".to_string(),
            "--split".to_string(),
            "1+s".to_string(),
        ]))
        .expect("command-line config");

        assert_eq!(config.listen.listen_ip, IpAddr::from_str("127.0.0.1").unwrap());
        assert_eq!(config.listen.listen_port, 2080);
    }

    #[test]
    fn rejects_non_runnable_command_line_payloads() {
        let err = runtime_config_from_command_line(vec!["ciadpi".to_string(), "--help".to_string()])
            .expect_err("help payload should not run");

        assert!(err.to_string().contains("runnable config"));
    }

    #[test]
    fn rejects_invalid_ui_proxy_port() {
        let err = runtime_config_from_payload(ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 0,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        }))
        .expect_err("port zero should be rejected");

        assert!(err.to_string().contains("Invalid proxy port"));
    }

    #[test]
    fn ui_payload_defaults_quic_settings_when_omitted() {
        let payload = parse_proxy_config_json(
            &serde_json::json!({
                "kind": "ui",
                "ip": "127.0.0.1",
                "port": 1080,
                "maxConnections": 512,
                "bufferSize": 16384,
                "defaultTtl": 0,
                "customTtl": false,
                "noDomain": false,
                "desyncHttp": true,
                "desyncHttps": true,
                "desyncUdp": false,
                "desyncMethod": "disorder",
                "splitMarker": "host+1",
                "tcpChainSteps": [],
                "splitPosition": 1,
                "splitAtHost": false,
                "fakeTtl": 8,
                "fakeSni": "www.iana.org",
                "oobChar": 97,
                "hostMixedCase": false,
                "domainMixedCase": false,
                "hostRemoveSpaces": false,
                "tlsRecordSplit": false,
                "tlsRecordSplitMarker": null,
                "tlsRecordSplitPosition": 0,
                "tlsRecordSplitAtSni": false,
                "hostsMode": "disable",
                "hosts": null,
                "tcpFastOpen": false,
                "udpFakeCount": 0,
                "udpChainSteps": [],
                "dropSack": false,
                "fakeOffsetMarker": null,
                "fakeOffset": 0
            })
            .to_string(),
        )
        .expect("parse ui payload");

        let config = runtime_config_from_payload(payload).expect("ui config");

        assert_eq!(config.quic_initial_mode, QuicInitialMode::RouteAndCache);
        assert!(config.quic_support_v1);
        assert!(config.quic_support_v2);
        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::Disabled);
        assert_eq!(config.groups[0].quic_fake_host, None);
    }

    #[test]
    fn rejects_unknown_quic_initial_mode_in_ui_payload() {
        let err = runtime_config_from_payload(ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("bogus".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        }))
        .expect_err("unknown quic mode should be rejected");

        assert!(err.to_string().contains("Unknown quicInitialMode"));
    }

    #[test]
    fn rejects_unknown_quic_fake_profile_in_ui_payload() {
        let err = runtime_config_from_payload(ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 1,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: "bogus".to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        }))
        .expect_err("unknown quic fake profile should be rejected");

        assert!(err.to_string().contains("Unknown quicFakeProfile"));
    }

    #[test]
    fn invalid_quic_fake_host_normalizes_to_absent() {
        let payload = ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: None,
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 1,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: "realistic_initial".to_string(),
            quic_fake_host: "127.0.0.1".to_string(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        });

        let config = runtime_config_from_payload(payload).expect("ui config");

        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].quic_fake_host, None);
    }

    #[test]
    fn rejects_invalid_proxy_json_payload() {
        let err = parse_proxy_config_json("{").expect_err("invalid json");

        assert!(err.to_string().contains("Invalid proxy config JSON"));
    }

    #[test]
    fn rejects_enabled_autolearn_without_store_path() {
        let err = runtime_config_from_payload(ui_payload(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: None,
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: true,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            strategy_preset: None,
        }))
        .expect_err("missing autolearn path should be rejected");

        assert!(err.to_string().contains("hostAutolearnStorePath is required when hostAutolearnEnabled is true"));
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
            split_position in -64i32..64i32,
            split_at_host in any::<bool>(),
            tls_record_split in any::<bool>(),
            tls_record_split_position in -64i32..64i32,
            tls_record_split_at_sni in any::<bool>(),
            tcp_fast_open in any::<bool>(),
            drop_sack in any::<bool>(),
            fake_offset in -64i32..64i32,
            udp_fake_count in 0i32..8i32,
            desync_method in prop_oneof![
                Just("none".to_string()),
                Just("split".to_string()),
                Just("disorder".to_string()),
                Just("fake".to_string()),
                Just("oob".to_string()),
                Just("disoob".to_string()),
            ],
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

            let config = runtime_config_from_ui(ProxyUiConfig {
                ip: ip.clone(),
                port,
                max_connections,
                buffer_size,
                default_ttl: 64,
                custom_ttl: true,
                no_domain: false,
                desync_http: true,
                desync_https: true,
                desync_udp: false,
                desync_method,
                split_marker: None,
                tcp_chain_steps: Vec::new(),
                group_activation_filter: ProxyUiActivationFilter::default(),
                split_position,
                split_at_host,
                fake_ttl: 8,
                adaptive_fake_ttl_enabled: false,
                adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
                adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
                adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
                adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
                fake_sni: "www.iana.org".to_string(),
                http_fake_profile: "compat_default".to_string(),
                fake_tls_use_original: false,
                fake_tls_randomize: false,
                fake_tls_dup_session_id: false,
                fake_tls_pad_encap: false,
                fake_tls_size: 0,
                fake_tls_sni_mode: default_fake_tls_sni_mode(),
                tls_fake_profile: "compat_default".to_string(),
                oob_char: b'a',
                host_mixed_case: false,
                domain_mixed_case: false,
                host_remove_spaces: false,
                http_method_eol: false,
                http_unix_eol: false,
                tls_record_split,
                tls_record_split_marker: None,
                tls_record_split_position,
                tls_record_split_at_sni,
                hosts_mode,
                hosts,
                tcp_fast_open,
                udp_fake_count,
                udp_chain_steps: Vec::new(),
                udp_fake_profile: "compat_default".to_string(),
                drop_sack,
                fake_offset_marker: None,
                fake_offset,
                quic_initial_mode: Some("route_and_cache".to_string()),
                quic_support_v1: true,
                quic_support_v2: true,
                quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
                quic_fake_host: String::new(),
                host_autolearn_enabled: false,
                host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
                host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
                host_autolearn_store_path: None,
                network_scope_key: None,
                strategy_preset: None,
            }).expect("valid payload");

            prop_assert_eq!(config.listen.listen_ip, IpAddr::from_str(&ip).expect("valid ip"));
            prop_assert_eq!(config.listen.listen_port, u16::try_from(port).expect("valid port"));
            prop_assert_eq!(config.max_open, max_connections);
            prop_assert_eq!(config.buffer_size, usize::try_from(buffer_size).expect("valid buffer size"));
            prop_assert_eq!(config.tfo, tcp_fast_open);
            prop_assert!(!config.groups.is_empty());
        }
    }
}
