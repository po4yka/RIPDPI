mod config;
mod diagnostics;
mod errors;
mod proxy;
mod telemetry;

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

#[cfg(test)]
use std::net::IpAddr;
#[cfg(test)]
use std::str::FromStr;
#[cfg(test)]
use std::sync::{Arc, Mutex};

#[cfg(test)]
use ciadpi_config::{
    QuicFakeProfile, QuicInitialMode, RuntimeConfig, TcpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND,
    FM_RNDSNI, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
};
#[cfg(test)]
use ripdpi_proxy_config::{
    ProxyConfigPayload, ProxyUiActivationFilter, ProxyUiConfig, ProxyUiTcpChainStep, FAKE_TLS_SNI_MODE_RANDOMIZED,
    QUIC_FAKE_PROFILE_DISABLED,
};
#[cfg(test)]
use ripdpi_runtime::{EmbeddedProxyControl, RuntimeTelemetrySink};

pub(crate) use config::{
    default_fake_tls_sni_mode, parse_proxy_config_json, runtime_config_from_command_line, runtime_config_from_payload,
    runtime_config_from_ui, HOSTS_BLACKLIST, HOSTS_DISABLE, HOSTS_WHITELIST,
};
use diagnostics::{
    diagnostics_cancel_scan_entry, diagnostics_create_entry, diagnostics_destroy_entry,
    diagnostics_poll_passive_events_entry, diagnostics_poll_progress_entry, diagnostics_start_scan_entry,
    diagnostics_take_report_entry,
};
pub(crate) use proxy::{
    ensure_proxy_destroyable, listener_fd_for_proxy_stop, lookup_proxy_session, open_proxy_listener,
    proxy_create_entry, proxy_destroy_entry, proxy_poll_telemetry_entry, proxy_start_entry, proxy_stop_entry,
    remove_proxy_session, shutdown_proxy_listener, try_mark_proxy_running, ProxySession, ProxySessionState, SESSIONS,
};
pub(crate) use telemetry::{NativeRuntimeSnapshot, ProxyTelemetryObserver, ProxyTelemetryState};

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    JNI_VERSION
}

macro_rules! export_diagnostics_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(env: JNIEnv, _thiz: JObject, $($arg: $arg_ty),*) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(
    env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    proxy_create_entry(env, config_json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    proxy_start_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_stop_entry(env, handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    proxy_poll_telemetry_entry(env, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy(
    env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    proxy_destroy_entry(env, handle);
}

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate,
    (),
    jlong,
    diagnostics_create_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan,
    (handle: jlong, request_json: JString, session_id: JString),
    (),
    diagnostics_start_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan,
    (handle: jlong),
    (),
    diagnostics_cancel_scan_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress,
    (handle: jlong),
    jstring,
    diagnostics_poll_progress_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport,
    (handle: jlong),
    jstring,
    diagnostics_take_report_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents,
    (handle: jlong),
    jstring,
    diagnostics_poll_passive_events_entry
);

export_diagnostics_jni!(
    Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy,
    (handle: jlong),
    (),
    diagnostics_destroy_entry
);

pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};

    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use serde_json::Value;

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

    #[test]
    fn open_proxy_listener_records_telemetry_when_bind_fails() {
        let busy = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind busy listener");
        let mut config = RuntimeConfig::default();
        config.listen.listen_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        config.listen.listen_port = busy.local_addr().expect("busy listener addr").port();
        let telemetry = ProxyTelemetryState::new();

        let err = open_proxy_listener(&config, &telemetry).expect_err("listener bind should fail");
        let snapshot = telemetry.snapshot();

        assert!(err.kind() == std::io::ErrorKind::AddrInUse || err.raw_os_error().is_some());
        assert_eq!(snapshot.total_errors, 1);
        assert!(snapshot.last_error.expect("listener error").contains("listener open failed"));
        assert_eq!(snapshot.health, "idle");
    }

    #[test]
    fn shutdown_proxy_listener_rejects_invalid_descriptor() {
        let err = shutdown_proxy_listener(-1).expect_err("invalid listener fd should fail");
        assert!(err.raw_os_error().is_some());
    }

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }

    #[test]
    fn rejects_unknown_proxy_handle_lookup() {
        let err = match lookup_proxy_session(99) {
            Ok(_) => panic!("expected unknown handle error"),
            Err(err) => err,
        };

        assert_eq!(err.to_string(), "Unknown proxy handle");
    }

    #[test]
    fn proxy_state_rejects_duplicate_start() {
        let mut state = ProxySessionState::Idle;
        let first = Arc::new(EmbeddedProxyControl::default());
        let second = Arc::new(EmbeddedProxyControl::default());

        try_mark_proxy_running(&mut state, 7, first).expect("first start");
        let err = try_mark_proxy_running(&mut state, 8, second).expect_err("duplicate start");

        assert_eq!(err, "Proxy session is already running");
    }

    #[test]
    fn proxy_state_rejects_stop_when_idle() {
        let err = listener_fd_for_proxy_stop(&ProxySessionState::Idle).expect_err("idle stop");

        assert_eq!(err, "Proxy session is not running");
    }

    #[test]
    fn proxy_state_rejects_destroy_when_running() {
        let err = ensure_proxy_destroyable(&ProxySessionState::Running {
            listener_fd: 9,
            control: Arc::new(EmbeddedProxyControl::default()),
        })
        .expect_err("running destroy");

        assert_eq!(err, "Cannot destroy a running proxy session");
    }

    #[test]
    fn proxy_telemetry_observer_updates_snapshot_and_drains_events() {
        let state = Arc::new(ProxyTelemetryState::new());
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_upstream_connected(target, Some(87));
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1);
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let first = state.snapshot();
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 0);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.route_changes, 1);
        assert_eq!(first.last_route_group, Some(2));
        assert_eq!(first.listener_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.upstream_address.as_deref(), Some("203.0.113.10:443"));
        assert_eq!(first.upstream_rtt_ms, Some(87));
        assert_eq!(first.last_target.as_deref(), Some("203.0.113.10:443"));
        assert_eq!(first.last_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert!(first.autolearn_enabled);
        assert_eq!(first.learned_host_count, 4);
        assert_eq!(first.penalized_host_count, 1);
        assert_eq!(first.last_autolearn_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_autolearn_group, Some(2));
        assert_eq!(first.last_autolearn_action.as_deref(), Some("host_promoted"));
        assert_eq!(first.native_events.len(), 5);

        let second = state.snapshot();
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_sessions, 1);

        observer.on_listener_stopped();
        let stopped = state.snapshot();
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert_eq!(stopped.native_events.len(), 1);
    }

    #[test]
    fn proxy_retry_pacing_telemetry_tracks_backoff_and_diversification_separately() {
        let state = Arc::new(ProxyTelemetryState::new());
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_retry_paced(target, 1, "same_signature_retry", 700);
        let paced = state.snapshot();
        assert_eq!(paced.retry_paced_count, 1);
        assert_eq!(paced.last_retry_backoff_ms, Some(700));
        assert_eq!(paced.last_retry_reason.as_deref(), Some("same_signature_retry"));
        assert_eq!(paced.candidate_diversification_count, 0);
        assert_eq!(paced.native_events.len(), 1);

        observer.on_retry_paced(target, 2, "candidate_order_diversified", 0);
        let diversified = state.snapshot();
        assert_eq!(diversified.retry_paced_count, 1);
        assert_eq!(diversified.last_retry_backoff_ms, None);
        assert_eq!(diversified.last_retry_reason.as_deref(), Some("candidate_order_diversified"));
        assert_eq!(diversified.candidate_diversification_count, 1);
        assert_eq!(diversified.native_events.len(), 1);
    }

    #[test]
    fn proxy_telemetry_snapshots_match_goldens() {
        let idle = ProxyTelemetryState::new().snapshot();
        assert_proxy_snapshot_golden("proxy_idle", &idle);

        let state = Arc::new(ProxyTelemetryState::new());
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_upstream_connected(target, Some(87));
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1);
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let running = state.snapshot();
        assert_proxy_snapshot_golden("proxy_running_degraded_first_poll", &running);

        let drained = state.snapshot();
        assert_proxy_snapshot_golden("proxy_running_degraded_second_poll", &drained);

        observer.on_listener_stopped();
        let stopped = state.snapshot();
        assert_proxy_snapshot_golden("proxy_stopped", &stopped);
    }

    #[test]
    fn destroy_removes_idle_proxy_session() {
        let handle = SESSIONS.insert(ProxySession {
            config: RuntimeConfig::default(),
            runtime_context: None,
            telemetry: Arc::new(ProxyTelemetryState::new()),
            state: Mutex::new(ProxySessionState::Idle),
        }) as jlong;

        let removed = remove_proxy_session(handle).expect("removed session");
        assert!(matches!(*removed.state.lock().expect("state lock"), ProxySessionState::Idle,));
        assert_eq!(
            match lookup_proxy_session(handle) {
                Ok(_) => panic!("expected session removal"),
                Err(err) => err.to_string(),
            },
            "Unknown proxy handle",
        );
    }

    #[derive(Clone, Copy, Debug)]
    enum ProxyStateCommand {
        EnsureCreated,
        Start,
        Stop,
        Destroy,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum ProxyModelState {
        Absent,
        Idle,
        Running,
    }

    struct ProxySessionHarness {
        active_handle: Option<jlong>,
        stale_handle: Option<jlong>,
        next_listener_fd: i32,
    }

    impl Default for ProxySessionHarness {
        fn default() -> Self {
            Self { active_handle: None, stale_handle: None, next_listener_fd: 32 }
        }
    }

    fn assert_proxy_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
        let actual = canonicalize_json_with(
            &serde_json::to_string(snapshot).expect("serialize proxy snapshot"),
            scrub_runtime_timestamps,
        )
        .expect("canonicalize proxy telemetry");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn scrub_runtime_timestamps(value: &mut Value) {
        match value {
            Value::Array(items) => {
                for item in items {
                    scrub_runtime_timestamps(item);
                }
            }
            Value::Object(map) => {
                for (key, item) in map.iter_mut() {
                    if matches!(key.as_str(), "createdAt" | "capturedAt") {
                        *item = Value::from(0);
                    } else {
                        scrub_runtime_timestamps(item);
                    }
                }
            }
            _ => {}
        }
    }

    impl ProxySessionHarness {
        fn tracked_handle(&self) -> jlong {
            self.active_handle.or(self.stale_handle).unwrap_or(0)
        }

        fn ensure_created(&mut self) -> jlong {
            if let Some(handle) = self.active_handle {
                return handle;
            }

            let handle = SESSIONS.insert(ProxySession {
                config: RuntimeConfig::default(),
                runtime_context: None,
                telemetry: Arc::new(ProxyTelemetryState::new()),
                state: Mutex::new(ProxySessionState::Idle),
            }) as jlong;
            self.active_handle = Some(handle);
            self.stale_handle = Some(handle);
            handle
        }

        fn start(&mut self) -> Result<i32, String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let listener_fd = self.next_listener_fd;
            let mut state = session.state.lock().expect("proxy state lock");
            try_mark_proxy_running(&mut state, listener_fd, Arc::new(EmbeddedProxyControl::default()))?;
            self.next_listener_fd += 1;
            Ok(listener_fd)
        }

        fn stop(&mut self) -> Result<i32, String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let mut state = session.state.lock().expect("proxy state lock");
            let (listener_fd, _) = listener_fd_for_proxy_stop(&state)?;
            *state = ProxySessionState::Idle;
            Ok(listener_fd)
        }

        fn destroy(&mut self) -> Result<(), String> {
            let session = lookup_proxy_session(self.tracked_handle()).map_err(|e| e.to_string())?;
            let state = session.state.lock().expect("proxy state lock");
            ensure_proxy_destroyable(&state)?;
            drop(state);
            let handle = self.active_handle.take().unwrap_or_else(|| self.tracked_handle());
            self.stale_handle = Some(handle);
            let _ = remove_proxy_session(handle).map_err(|e| e.to_string())?;
            Ok(())
        }

        fn cleanup(&mut self) {
            if let Some(handle) = self.active_handle.take() {
                if let Ok(session) = lookup_proxy_session(handle) {
                    if let Ok(mut state) = session.state.lock() {
                        *state = ProxySessionState::Idle;
                    }
                }
                let _ = remove_proxy_session(handle);
                self.stale_handle = Some(handle);
            }
        }
    }

    impl Drop for ProxySessionHarness {
        fn drop(&mut self) {
            self.cleanup();
        }
    }

    fn proxy_absent_error(handle: jlong) -> String {
        if to_handle(handle).is_some() {
            "Unknown proxy handle".to_string()
        } else {
            "Invalid proxy handle".to_string()
        }
    }

    fn proxy_state_command_strategy() -> impl Strategy<Value = Vec<ProxyStateCommand>> {
        vec(
            prop_oneof![
                Just(ProxyStateCommand::EnsureCreated),
                Just(ProxyStateCommand::Start),
                Just(ProxyStateCommand::Stop),
                Just(ProxyStateCommand::Destroy),
            ],
            1..32,
        )
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

        #[test]
        fn proxy_session_state_machine(commands in proxy_state_command_strategy()) {
            let mut harness = ProxySessionHarness::default();
            let mut model = ProxyModelState::Absent;
            let mut expected_listener_fd = 32;

            for command in commands {
                match command {
                    ProxyStateCommand::EnsureCreated => {
                        let handle = harness.ensure_created();
                        prop_assert!(lookup_proxy_session(handle).is_ok());
                        if matches!(model, ProxyModelState::Absent) {
                            model = ProxyModelState::Idle;
                        }
                    }
                    ProxyStateCommand::Start => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.start().expect_err("absent start must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                let listener_fd = harness.start().expect("idle start");
                                prop_assert_eq!(listener_fd, expected_listener_fd);
                                expected_listener_fd += 1;
                                model = ProxyModelState::Running;
                            }
                            ProxyModelState::Running => {
                                let err = harness.start().expect_err("duplicate start must fail");
                                prop_assert_eq!(err, "Proxy session is already running");
                            }
                        }
                    }
                    ProxyStateCommand::Stop => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.stop().expect_err("absent stop must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                let err = harness.stop().expect_err("idle stop must fail");
                                prop_assert_eq!(err, "Proxy session is not running");
                            }
                            ProxyModelState::Running => {
                                let listener_fd = harness.stop().expect("running stop");
                                prop_assert!(listener_fd >= 32);
                                model = ProxyModelState::Idle;
                            }
                        }
                    }
                    ProxyStateCommand::Destroy => {
                        match model {
                            ProxyModelState::Absent => {
                                let err = harness.destroy().expect_err("absent destroy must fail");
                                prop_assert_eq!(err, proxy_absent_error(harness.tracked_handle()));
                            }
                            ProxyModelState::Idle => {
                                harness.destroy().expect("idle destroy");
                                model = ProxyModelState::Absent;
                            }
                            ProxyModelState::Running => {
                                let err = harness.destroy().expect_err("running destroy must fail");
                                prop_assert_eq!(err, "Cannot destroy a running proxy session");
                            }
                        }
                    }
                }

                match model {
                    ProxyModelState::Absent => {
                        if to_handle(harness.tracked_handle()).is_some() {
                            let err = match lookup_proxy_session(harness.tracked_handle()) {
                                Ok(_) => panic!("absent session must be removed"),
                                Err(err) => err.to_string(),
                            };
                            prop_assert_eq!(err, "Unknown proxy handle");
                        }
                    }
                    ProxyModelState::Idle => {
                        let session = lookup_proxy_session(harness.tracked_handle()).expect("idle session");
                        let state = session.state.lock().expect("proxy state lock");
                        prop_assert!(matches!(*state, ProxySessionState::Idle));
                    }
                    ProxyModelState::Running => {
                        let session = lookup_proxy_session(harness.tracked_handle()).expect("running session");
                        let state = session.state.lock().expect("proxy state lock");
                        let is_running = matches!(*state, ProxySessionState::Running { .. });
                        prop_assert!(is_running);
                    }
                }
            }
        }
    }
}
