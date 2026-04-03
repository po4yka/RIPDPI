use super::validate_scan_request;
use crate::types::StrategyProbeCompletionKind;
use crate::*;

use crate::candidates::{
    build_tcp_candidates, candidate_pause_ms, candidate_spec, default_runtime_encrypted_dns_context,
    target_probe_pause_ms,
};
use crate::classification::{
    classify_strategy_probe_baseline_results, interleave_candidate_families, next_candidate_index,
    reorder_tcp_candidates_for_failure,
};
use crate::connectivity::{run_dns_probe, run_domain_probe, run_tcp_probe};
use crate::execution::freeze_adaptive_fake_ttl_for_probe;
use crate::strategy::detect_strategy_probe_dns_tampering;
use crate::test_fixtures::*;
use crate::tls::{classify_tls_signal, try_tls_handshake, NoCertificateVerification, TlsClientProfile, TlsObservation};
use crate::transport::{TargetAddress, TransportConfig};
use crate::util::{probe_session_seed, DEFAULT_DNS_SERVER};

use ripdpi_failure_classifier::{FailureAction, FailureClass};
use ripdpi_proxy_config::{
    parse_proxy_config_json, ProxyConfigPayload, ProxyEncryptedDnsContext, ProxyRuntimeContext, ProxyUiConfig,
};

use std::net::{IpAddr, Ipv4Addr};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::Duration;

/// Serializes tests that spawn local TCP/TLS servers to avoid thread-starvation
/// timeouts on resource-constrained hosts (e.g., Raspberry Pi).
static NETWORK_PROBE_LOCK: Mutex<()> = Mutex::new(());
fn lock_network_probes() -> MutexGuard<'static, ()> {
    NETWORK_PROBE_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn minimal_ui_config() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

fn strategy_probe_request(base_ui: ProxyUiConfig) -> ScanRequest {
    strategy_probe_request_with_suite(base_ui, "quick_v1", "automatic-probing", "Automatic probing")
}

fn strategy_probe_request_with_runtime_context(
    base_ui: ProxyUiConfig,
    suite_id: &str,
    profile_id: &str,
    display_name: &str,
    runtime_context: Option<ProxyRuntimeContext>,
) -> ScanRequest {
    ScanRequest {
        profile_id: profile_id.to_string(),
        display_name: display_name.to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::StrategyProbe,
        family: if suite_id == "full_matrix_v1" {
            DiagnosticProfileFamily::AutomaticAudit
        } else {
            DiagnosticProfileFamily::AutomaticProbing
        },
        region_tag: None,
        manual_only: false,
        pack_refs: vec![],
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![],
        domain_targets: vec![DomainTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: None,
            https_port: Some(9),
            http_port: Some(8080),
            http_path: "/".to_string(),
            is_control: false,
        }],
        dns_targets: vec![],
        tcp_targets: vec![],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![],
        telegram_target: None,
        strategy_probe: Some(StrategyProbeRequest {
            suite_id: suite_id.to_string(),
            base_proxy_config_json: Some(
                serde_json::to_string(&ProxyConfigPayload::Ui {
                    strategy_preset: None,
                    config: base_ui,
                    runtime_context,
                    log_context: None,
                })
                .expect("serialize probe ui config"),
            ),
            target_selection: None,
        }),
        network_snapshot: None,
    }
}

fn strategy_probe_request_with_suite(
    base_ui: ProxyUiConfig,
    suite_id: &str,
    profile_id: &str,
    display_name: &str,
) -> ScanRequest {
    strategy_probe_request_with_runtime_context(base_ui, suite_id, profile_id, display_name, None)
}

fn decode_ui_config(config_json: &str) -> ProxyUiConfig {
    match parse_proxy_config_json(config_json).expect("parse ui config") {
        ProxyConfigPayload::Ui { config, .. } => config,
        ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
    }
}

fn assert_strategy_probe_recommendation_matches_winners(strategy_probe: &crate::types::StrategyProbeReport) {
    let winning_tcp = strategy_probe
        .tcp_candidates
        .iter()
        .find(|candidate| candidate.id == strategy_probe.recommendation.tcp_candidate_id)
        .expect("winning TCP candidate must exist");
    let winning_quic = strategy_probe
        .quic_candidates
        .iter()
        .find(|candidate| candidate.id == strategy_probe.recommendation.quic_candidate_id)
        .expect("winning QUIC candidate must exist");

    assert!(!winning_tcp.skipped, "recommended TCP candidate must be a non-skipped candidate in the list");
    assert!(!winning_quic.skipped, "recommended QUIC candidate must be a non-skipped candidate in the list");

    let recommended_config = decode_ui_config(&strategy_probe.recommendation.recommended_proxy_config_json);
    let winning_tcp_config =
        decode_ui_config(winning_tcp.proxy_config_json.as_deref().expect("winning TCP candidate config"));
    let winning_quic_config =
        decode_ui_config(winning_quic.proxy_config_json.as_deref().expect("winning QUIC candidate config"));

    assert_eq!(
        recommended_config.chains.tcp_steps, winning_tcp_config.chains.tcp_steps,
        "recommended config must carry the winning TCP steps"
    );
    assert_eq!(
        recommended_config.chains.group_activation_filter, winning_tcp_config.chains.group_activation_filter,
        "recommended config must carry the winning TCP activation filter"
    );
    assert_eq!(
        recommended_config.fake_packets, winning_tcp_config.fake_packets,
        "recommended config must carry the winning TCP fake-packet settings"
    );
    assert_eq!(
        recommended_config.parser_evasions, winning_tcp_config.parser_evasions,
        "recommended config must carry the winning TCP parser evasions"
    );
    assert_eq!(
        recommended_config.protocols.desync_http, winning_tcp_config.protocols.desync_http,
        "recommended config must carry the winning TCP HTTP toggle"
    );
    assert_eq!(
        recommended_config.protocols.desync_https, winning_tcp_config.protocols.desync_https,
        "recommended config must carry the winning TCP HTTPS toggle"
    );
    assert_eq!(
        recommended_config.protocols.desync_udp, winning_quic_config.protocols.desync_udp,
        "recommended config must carry the winning QUIC UDP toggle"
    );
    assert_eq!(
        recommended_config.chains.udp_steps, winning_quic_config.chains.udp_steps,
        "recommended config must carry the winning QUIC UDP steps"
    );
    assert_eq!(
        recommended_config.quic, winning_quic_config.quic,
        "recommended config must carry the winning QUIC settings"
    );
    assert_eq!(
        winning_quic_config.chains.tcp_steps, winning_tcp_config.chains.tcp_steps,
        "winning QUIC candidate config must already include the winning TCP steps"
    );
    assert_eq!(
        winning_quic_config.chains.group_activation_filter, winning_tcp_config.chains.group_activation_filter,
        "winning QUIC candidate config must already include the winning TCP activation filter"
    );
}

#[test]
fn probe_transport_freezes_adaptive_fake_ttl_to_seed() {
    let mut config = minimal_ui_config();
    config.fake_packets.fake_ttl = 11;
    config.fake_packets.adaptive_fake_ttl_enabled = true;
    config.fake_packets.adaptive_fake_ttl_delta = -1;
    config.fake_packets.adaptive_fake_ttl_min = 3;
    config.fake_packets.adaptive_fake_ttl_max = 9;
    config.fake_packets.adaptive_fake_ttl_fallback = 13;

    freeze_adaptive_fake_ttl_for_probe(&mut config);

    assert_eq!(config.fake_packets.fake_ttl, 9);
    assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
}

#[test]
fn probe_transport_uses_fake_ttl_when_adaptive_fallback_is_invalid() {
    let mut config = minimal_ui_config();
    config.fake_packets.fake_ttl = 7;
    config.fake_packets.adaptive_fake_ttl_enabled = true;
    config.fake_packets.adaptive_fake_ttl_min = 3;
    config.fake_packets.adaptive_fake_ttl_max = 12;
    config.fake_packets.adaptive_fake_ttl_fallback = 0;

    freeze_adaptive_fake_ttl_for_probe(&mut config);

    assert_eq!(config.fake_packets.fake_ttl, 7);
    assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
}

#[test]
fn dns_probe_reports_substitution_when_udp_and_doh_differ() {
    let _serial = lock_network_probes();
    let udp = UdpDnsServer::start("203.0.113.10");
    let doh = HttpTextServer::start_dns_message("198.51.100.77");
    let target = DnsTarget {
        domain: "blocked.example".to_string(),
        udp_server: Some(udp.addr()),
        encrypted_resolver_id: None,
        encrypted_protocol: Some("doh".to_string()),
        encrypted_host: Some("127.0.0.1".to_string()),
        encrypted_port: Some(doh.port()),
        encrypted_tls_server_name: None,
        encrypted_bootstrap_ips: vec!["127.0.0.1".to_string()],
        encrypted_doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
        encrypted_dnscrypt_provider_name: None,
        encrypted_dnscrypt_public_key: None,
        expected_ips: vec![],
    };

    let result = run_dns_probe(&target, &TransportConfig::Direct, &ScanPathMode::RawPath);
    assert_eq!(result.outcome, "dns_substitution");
}

#[test]
fn dns_probe_reports_doh_blocked_when_udp_works_and_doh_fails() {
    let _serial = lock_network_probes();
    let udp = UdpDnsServer::start("203.0.113.10");
    let target = DnsTarget {
        domain: "blocked.example".to_string(),
        udp_server: Some(udp.addr()),
        encrypted_resolver_id: None,
        encrypted_protocol: None,
        encrypted_host: None,
        encrypted_port: None,
        encrypted_tls_server_name: None,
        encrypted_bootstrap_ips: vec!["127.0.0.1".to_string()],
        encrypted_doh_url: Some("http://127.0.0.1:9/dns-query".to_string()),
        encrypted_dnscrypt_provider_name: None,
        encrypted_dnscrypt_public_key: None,
        expected_ips: vec![],
    };

    let result = run_dns_probe(&target, &TransportConfig::Direct, &ScanPathMode::RawPath);
    assert_eq!(result.outcome, "encrypted_dns_blocked");
}

#[test]
fn dns_probe_reports_match_over_socks5_udp_and_doh() {
    let _serial = lock_network_probes();
    let udp = UdpDnsServer::start("203.0.113.10");
    let doh = HttpTextServer::start_dns_message("203.0.113.10");
    let proxy = Socks5RelayServer::start();
    let target = DnsTarget {
        domain: "blocked.example".to_string(),
        udp_server: Some(udp.addr()),
        encrypted_resolver_id: None,
        encrypted_protocol: None,
        encrypted_host: None,
        encrypted_port: None,
        encrypted_tls_server_name: None,
        encrypted_bootstrap_ips: Vec::new(),
        encrypted_doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
        encrypted_dnscrypt_provider_name: None,
        encrypted_dnscrypt_public_key: None,
        expected_ips: vec![],
    };

    let result = run_dns_probe(
        &target,
        &TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: proxy.port() },
        &ScanPathMode::InPath,
    );

    println!("dns socks result: {result:?}");
    assert_eq!(result.outcome, "dns_match");
    assert!(proxy.udp_associate_attempts() >= 1);
}

#[test]
fn domain_probe_reports_tls_certificate_anomaly() {
    let _serial = lock_network_probes();
    let server = TlsHttpServer::start(TlsMode::Single("localhost".to_string()), FatServerMode::AlwaysOk);
    let target = DomainTarget {
        host: "localhost".to_string(),
        connect_ip: None,
        https_port: Some(server.port()),
        http_port: Some(9),
        http_path: "/".to_string(),
        is_control: false,
    };

    let result = run_domain_probe(&target, &TransportConfig::Direct, None);
    assert_eq!(result.outcome, "tls_cert_invalid");
}

#[test]
fn tls_signal_reports_version_split_low_confidence() {
    let tls13 = TlsObservation {
        status: "tls_handshake_failed".to_string(),
        version: None,
        error: Some("blocked".to_string()),
        certificate_anomaly: false,
        ech_resolution_detail: None,
        tcp_connect_ms: None,
        tls_handshake_ms: None,
        cert_chain_length: None,
        cert_issuer: None,
        observed_server_ttl: None,
        estimated_hop_count: None,
        ja3_fingerprint: None,
    };
    let tls12 = TlsObservation {
        status: "tls_ok".to_string(),
        version: Some("TLS1.2".to_string()),
        error: None,
        certificate_anomaly: false,
        ech_resolution_detail: None,
        tcp_connect_ms: None,
        tls_handshake_ms: None,
        cert_chain_length: None,
        cert_issuer: None,
        observed_server_ttl: None,
        estimated_hop_count: None,
        ja3_fingerprint: None,
    };

    assert_eq!(classify_tls_signal(&tls13, &tls12), "tls_version_split_low_confidence");
}

#[test]
fn try_tls_handshake_forces_tls_on_non_default_https_port() {
    let _serial = lock_network_probes();
    let server = TlsHttpServer::start(TlsMode::Single("localhost".to_string()), FatServerMode::AlwaysOk);
    let target = TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let no_verify: Arc<dyn rustls::client::danger::ServerCertVerifier> = Arc::new(NoCertificateVerification);
    let mut tls = try_tls_handshake(
        &target,
        server.port(),
        &TransportConfig::Direct,
        "localhost",
        false,
        TlsClientProfile::Tls13Only,
        Some(&no_verify),
    );
    for _ in 0..4 {
        if tls.status == "tls_ok" {
            break;
        }
        thread::sleep(Duration::from_millis(25));
        tls = try_tls_handshake(
            &target,
            server.port(),
            &TransportConfig::Direct,
            "localhost",
            false,
            TlsClientProfile::Tls13Only,
            Some(&no_verify),
        );
    }

    assert_eq!(tls.status, "tls_ok");
    assert!(tls.version.is_some());
}

#[test]
fn domain_probe_reports_http_blockpage() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
    let target = DomainTarget {
        host: "127.0.0.1".to_string(),
        connect_ip: None,
        https_port: Some(9),
        http_port: Some(server.port()),
        http_path: "/".to_string(),
        is_control: false,
    };

    let result = run_domain_probe(&target, &TransportConfig::Direct, None);
    assert_eq!(result.outcome, "http_blockpage");
}

#[test]
fn tcp_probe_reports_threshold_cutoff() {
    let _serial = lock_network_probes();
    let server = PlainFatHeaderServer::start(FatServerMode::CutoffAtThreshold);
    let target = TcpTarget {
        id: "test".to_string(),
        provider: "plain-fat".to_string(),
        ip: "127.0.0.1".to_string(),
        port: server.port(),
        sni: None,
        asn: None,
        host_header: Some("plain-fat".to_string()),
        fat_header_requests: Some(16),
    };

    let result = run_tcp_probe(&target, &[], &TransportConfig::Direct);
    assert!(
        matches!(result.outcome.as_str(), "tcp_16kb_blocked" | "tcp_reset"),
        "unexpected cutoff outcome: {}",
        result.outcome
    );
}

#[test]
fn tcp_probe_reports_whitelist_sni_success() {
    let _serial = lock_network_probes();
    let server = PlainFatHeaderServer::start(FatServerMode::AllowHost("allow.example".to_string()));
    let target = TcpTarget {
        id: "test".to_string(),
        provider: "tls-fat".to_string(),
        ip: "127.0.0.1".to_string(),
        port: server.port(),
        sni: Some("deny.example".to_string()),
        asn: Some("AS1337".to_string()),
        host_header: Some("deny.example".to_string()),
        fat_header_requests: Some(8),
    };

    let result =
        run_tcp_probe(&target, &["allow.example".to_string(), "other.example".to_string()], &TransportConfig::Direct);
    assert_eq!(result.outcome, "whitelist_sni_ok");
}

#[test]
fn tcp_probe_reports_whitelist_sni_failure() {
    let _serial = lock_network_probes();
    let server = PlainFatHeaderServer::start(FatServerMode::AllowHost("allow.example".to_string()));
    let target = TcpTarget {
        id: "test".to_string(),
        provider: "tls-fat".to_string(),
        ip: "127.0.0.1".to_string(),
        port: server.port(),
        sni: Some("deny.example".to_string()),
        asn: Some("AS1337".to_string()),
        host_header: Some("deny.example".to_string()),
        fat_header_requests: Some(8),
    };

    let result = run_tcp_probe(&target, &["missing.example".to_string()], &TransportConfig::Direct);
    assert_eq!(result.outcome, "whitelist_sni_failed");
}

#[test]
fn tcp_probe_skips_whitelist_when_sni_is_none() {
    let _serial = lock_network_probes();
    let server = PlainFatHeaderServer::start(FatServerMode::AllowHost("allow.example".to_string()));
    let target = TcpTarget {
        id: "test".to_string(),
        provider: "plain-fat".to_string(),
        ip: "127.0.0.1".to_string(),
        port: server.port(),
        sni: None,
        asn: Some("AS1337".to_string()),
        host_header: None,
        fat_header_requests: Some(8),
    };

    let result =
        run_tcp_probe(&target, &["allow.example".to_string(), "other.example".to_string()], &TransportConfig::Direct);
    assert!(!result.outcome.starts_with("whitelist_sni_"), "expected non-whitelist outcome, got: {}", result.outcome);
}

#[test]
fn strategy_probe_request_requires_base_ui_config() {
    let mut request = strategy_probe_request(minimal_ui_config());
    request.strategy_probe.as_mut().expect("strategy probe").base_proxy_config_json = None;

    let err = validate_scan_request(&request.into()).expect_err("missing base config should fail");

    assert_eq!(err, "strategy_probe scan requires baseProxyConfigJson");
}

#[test]
fn strategy_probe_request_rejects_command_line_config_payload() {
    let mut request = strategy_probe_request(minimal_ui_config());
    request.strategy_probe = Some(StrategyProbeRequest {
        suite_id: "quick_v1".to_string(),
        base_proxy_config_json: Some(
            serde_json::to_string(&ProxyConfigPayload::CommandLine {
                args: vec!["ripdpi".to_string(), "--split".to_string()],
                host_autolearn_store_path: None,
                runtime_context: None,
                log_context: None,
            })
            .expect("serialize command line payload"),
        ),
        target_selection: None,
    });

    let err = validate_scan_request(&request.into()).expect_err("command line payload should fail");

    assert_eq!(err, "strategy_probe scans only support UI proxy config");
}

#[test]
fn tcp_candidate_catalog_keeps_current_strategy_first() {
    let candidates = build_tcp_candidates(&minimal_ui_config());

    assert_eq!(candidates.first().map(|candidate| candidate.id), Some("baseline_current"));
    assert_eq!(candidates.len(), 13);
    assert_eq!(candidates.get(1).map(|candidate| candidate.id), Some("parser_only"));
    assert_eq!(candidates.get(2).map(|candidate| candidate.id), Some("parser_unixeol"));
    assert_eq!(candidates.get(3).map(|candidate| candidate.id), Some("parser_methodeol"));
    assert!(candidates.iter().any(|candidate| candidate.id == "ech_split"));
    assert!(candidates.iter().any(|candidate| candidate.id == "ech_tlsrec"));
    assert_eq!(
        candidates.iter().find(|candidate| candidate.id == "ech_split").map(|candidate| candidate.family),
        Some("ech_split")
    );
    assert_eq!(
        candidates.iter().find(|candidate| candidate.id == "ech_tlsrec").map(|candidate| candidate.family),
        Some("ech_tlsrec")
    );
}

#[test]
fn http_blockpage_reorders_tcp_candidates_toward_parser_families() {
    let ordered = reorder_tcp_candidates_for_failure(
        &build_tcp_candidates(&minimal_ui_config()),
        Some(FailureClass::HttpBlockpage),
    );
    let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["baseline_current", "parser_only", "parser_unixeol", "split_host"]);
}

#[test]
fn tcp_reset_reorders_tcp_candidates_toward_split_families() {
    let ordered =
        reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::TcpReset));
    let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake_split"]);
}

#[test]
fn silent_drop_reorders_tcp_candidates_toward_fake_tls_families() {
    let ordered =
        reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::SilentDrop));
    let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["baseline_current", "tlsrec_fake_rich", "tlsrec_hostfake", "tlsrec_hostfake_split"]);
}

#[test]
fn tls_alert_reorders_tcp_candidates_away_from_fake_heavy_paths() {
    let ordered =
        reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::TlsAlert));
    let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake"]);
}

#[test]
fn interleaving_probe_candidates_breaks_same_family_blocks() {
    let base = minimal_ui_config();
    let ordered = interleave_candidate_families(
        vec![
            candidate_spec("parser_a", "Parser A", "parser", base.clone()),
            candidate_spec("parser_b", "Parser B", "parser", base.clone()),
            candidate_spec("split_a", "Split A", "split", base.clone()),
            candidate_spec("hostfake_a", "Hostfake A", "hostfake", base),
        ],
        42,
    );
    let families = ordered.iter().map(|candidate| candidate.family).collect::<Vec<_>>();

    assert_eq!(families.len(), 4);
    assert_ne!(families[0], families[1]);
    assert!(families.windows(3).any(|window| window[0] != window[1] && window[1] != window[2]));
}

#[test]
fn blocked_family_selection_prefers_another_family_when_available() {
    let base = minimal_ui_config();
    let candidates = vec![
        candidate_spec("parser_a", "Parser A", "parser", base.clone()),
        candidate_spec("parser_b", "Parser B", "parser", base.clone()),
        candidate_spec("split_a", "Split A", "split", base),
    ];

    let index = next_candidate_index(&candidates, Some("parser"));

    assert_eq!(candidates[index].family, "split");
}

#[test]
fn probe_session_seed_is_stable_and_scope_sensitive() {
    let stable_a = probe_session_seed(Some("scope-a"), "session-1");
    let stable_b = probe_session_seed(Some("scope-a"), "session-1");
    let different_scope = probe_session_seed(Some("scope-b"), "session-1");
    let different_session = probe_session_seed(Some("scope-a"), "session-2");

    assert_eq!(stable_a, stable_b);
    assert_ne!(stable_a, different_scope);
    assert_ne!(stable_a, different_session);
}

#[test]
fn candidate_pause_ranges_match_success_and_failure_budgets() {
    let candidate = candidate_spec("parser_a", "Parser A", "parser", minimal_ui_config());
    let normal = candidate_pause_ms(42, &candidate, false);
    let failed = candidate_pause_ms(42, &candidate, true);

    assert!((120..=350).contains(&normal));
    assert!((400..=900).contains(&failed));
}

#[test]
fn target_probe_pause_is_deterministic_for_same_seed_candidate_and_target() {
    let candidate = candidate_spec("parser_a", "Parser A", "parser", minimal_ui_config());

    let first = target_probe_pause_ms(77, &candidate, "example.org");
    let second = target_probe_pause_ms(77, &candidate, "example.org");
    let different = target_probe_pause_ms(78, &candidate, "example.org");

    assert_eq!(first, second);
    assert!((120..=350).contains(&first));
    assert_ne!(first, different);
}

#[test]
fn baseline_dns_tampering_uses_runtime_context_before_candidate_trials() {
    let _serial = lock_network_probes();
    let doh = HttpTextServer::start_dns_message("198.51.100.11");
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: Some("doh".to_string()),
            protocol: "doh".to_string(),
            host: "127.0.0.1".to_string(),
            port: doh.port(),
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".to_string()],
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
    };

    let baseline = detect_strategy_probe_dns_tampering(
        &[DomainTarget {
            host: "blocked.example".to_string(),
            connect_ip: Some("203.0.113.10".to_string()),
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".to_string(),
            is_control: false,
        }],
        Some(&runtime_context),
    )
    .expect("dns tampering");

    assert_eq!(baseline.failure.class, FailureClass::DnsTampering);
    assert_eq!(baseline.failure.action, FailureAction::ResolverOverrideRecommended);
    assert_eq!(baseline.results.first().map(|result| result.outcome.as_str()), Some("dns_substitution"));
}

#[test]
fn quic_probe_failures_are_surfaced_as_quic_breakage() {
    let failure = classify_strategy_probe_baseline_results(&[ProbeResult {
        probe_type: "strategy_quic".to_string(),
        target: "Current QUIC strategy".to_string(),
        outcome: "quic_empty".to_string(),
        details: vec![ProbeDetail { key: "error".to_string(), value: "none".to_string() }],
    }])
    .expect("quic failure");

    assert_eq!(failure.class, FailureClass::QuicBreakage);
    assert_eq!(failure.action, FailureAction::DiagnosticsOnly);
}

#[test]
fn aggressive_parser_candidates_enable_only_expected_evasion() {
    let candidates = build_tcp_candidates(&minimal_ui_config());
    let unixeol = candidates.iter().find(|candidate| candidate.id == "parser_unixeol").expect("unixeol candidate");
    let methodeol =
        candidates.iter().find(|candidate| candidate.id == "parser_methodeol").expect("methodeol candidate");

    assert!(unixeol.config.parser_evasions.host_mixed_case);
    assert!(unixeol.config.parser_evasions.domain_mixed_case);
    assert!(unixeol.config.parser_evasions.host_remove_spaces);
    assert!(unixeol.config.parser_evasions.http_unix_eol);
    assert!(!unixeol.config.parser_evasions.http_method_eol);

    assert!(methodeol.config.parser_evasions.host_mixed_case);
    assert!(methodeol.config.parser_evasions.domain_mixed_case);
    assert!(methodeol.config.parser_evasions.host_remove_spaces);
    assert!(methodeol.config.parser_evasions.http_method_eol);
    assert!(!methodeol.config.parser_evasions.http_unix_eol);
}

#[test]
fn parser_only_candidate_keeps_aggressive_http_evasions_disabled() {
    let candidates = build_tcp_candidates(&minimal_ui_config());
    let parser_only = candidates.iter().find(|candidate| candidate.id == "parser_only").expect("parser_only candidate");

    assert!(parser_only.config.parser_evasions.host_mixed_case);
    assert!(parser_only.config.parser_evasions.domain_mixed_case);
    assert!(parser_only.config.parser_evasions.host_remove_spaces);
    assert!(!parser_only.config.parser_evasions.http_method_eol);
    assert!(!parser_only.config.parser_evasions.http_unix_eol);
}

#[test]
fn monitor_session_strategy_probe_returns_structured_recommendation() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    let mut request = strategy_probe_request(minimal_ui_config());
    request.domain_targets[0].http_port = Some(server.port());
    let session = MonitorSession::new();

    session.start_scan("session-strategy".to_string(), request.into()).expect("start strategy probe");
    let report = wait_for_report(&session);
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");

    assert_eq!(report.profile_id, "automatic-probing");
    assert_eq!(strategy_probe.tcp_candidates.first().map(|candidate| candidate.id.as_str()), Some("baseline_current"));
    assert_strategy_probe_recommendation_matches_winners(&strategy_probe);
    assert!(strategy_probe.audit_assessment.is_none());
}

#[test]
fn monitor_session_strategy_probe_marks_dns_short_circuit_completion_kind() {
    let _serial = lock_network_probes();
    let doh = HttpTextServer::start_dns_message("198.51.100.11");
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: Some("doh".to_string()),
            protocol: "doh".to_string(),
            host: "127.0.0.1".to_string(),
            port: doh.port(),
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".to_string()],
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
    };
    let mut request = strategy_probe_request_with_runtime_context(
        minimal_ui_config(),
        "quick_v1",
        "automatic-probing",
        "Automatic probing",
        Some(runtime_context),
    );
    request.domain_targets = vec![DomainTarget {
        host: "blocked.example".to_string(),
        connect_ip: Some("203.0.113.10".to_string()),
        https_port: Some(443),
        http_port: Some(80),
        http_path: "/".to_string(),
        is_control: false,
    }];
    let session = MonitorSession::new();

    session.start_scan("session-strategy-dns-short".to_string(), request.into()).expect("start strategy probe");
    let report = wait_for_report(&session);
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");

    assert_eq!(strategy_probe.completion_kind, StrategyProbeCompletionKind::DnsTamperingWithFallback);
}

#[test]
fn monitor_session_full_matrix_strategy_probe_reports_audit_assessment() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    let mut request =
        strategy_probe_request_with_suite(minimal_ui_config(), "full_matrix_v1", "automatic-audit", "Automatic audit");
    request.domain_targets = vec![
        DomainTarget {
            host: "www.youtube.com".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        },
        DomainTarget {
            host: "discord.com".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        },
        DomainTarget {
            host: "proton.me".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        },
    ];
    request.quic_targets = vec![
        QuicTarget { host: "www.youtube.com".to_string(), connect_ip: Some("127.0.0.1".to_string()), port: 9 },
        QuicTarget { host: "discord.com".to_string(), connect_ip: Some("127.0.0.1".to_string()), port: 9 },
    ];
    request.strategy_probe.as_mut().expect("strategy probe").target_selection = Some(StrategyProbeTargetSelection {
        cohort_id: "global-core".to_string(),
        cohort_label: "Global core mix".to_string(),
        domain_hosts: request.domain_targets.iter().map(|target| target.host.clone()).collect(),
        quic_hosts: request.quic_targets.iter().map(|target| target.host.clone()).collect(),
    });
    let expected_domain_hosts = request.domain_targets.iter().map(|target| target.host.clone()).collect::<Vec<_>>();
    let expected_quic_hosts = request.quic_targets.iter().map(|target| target.host.clone()).collect::<Vec<_>>();
    let session = MonitorSession::new();

    session.start_scan("session-audit".to_string(), request.into()).expect("start automatic audit");
    let report = wait_for_report(&session);
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");
    let audit_assessment = strategy_probe.audit_assessment.as_ref().expect("audit assessment");

    assert_eq!(report.profile_id, "automatic-audit");
    assert_eq!(strategy_probe.suite_id, "full_matrix_v1");
    assert!(
        strategy_probe
            .tcp_candidates
            .iter()
            .any(|candidate| candidate.id == strategy_probe.recommendation.tcp_candidate_id),
        "recommended TCP candidate must exist in the candidate list",
    );
    assert!(
        strategy_probe
            .quic_candidates
            .iter()
            .any(|candidate| candidate.id == strategy_probe.recommendation.quic_candidate_id),
        "recommended QUIC candidate must exist in the candidate list",
    );
    let target_selection = strategy_probe.target_selection.as_ref().expect("target selection");
    assert_eq!(target_selection.cohort_id, "global-core");
    assert_eq!(target_selection.domain_hosts, expected_domain_hosts);
    assert_eq!(target_selection.quic_hosts, expected_quic_hosts);
    assert!(audit_assessment.coverage.tcp_candidates_planned >= strategy_probe.tcp_candidates.len());
    assert!(audit_assessment.coverage.quic_candidates_planned >= strategy_probe.quic_candidates.len());
}

#[test]
fn monitor_session_full_matrix_marks_dns_short_circuit_completion_kind() {
    let _serial = lock_network_probes();
    let doh = HttpTextServer::start_dns_message("198.51.100.11");
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: Some("doh".to_string()),
            protocol: "doh".to_string(),
            host: "127.0.0.1".to_string(),
            port: doh.port(),
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".to_string()],
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
    };
    let mut request = strategy_probe_request_with_runtime_context(
        minimal_ui_config(),
        "full_matrix_v1",
        "automatic-audit",
        "Automatic audit",
        Some(runtime_context),
    );
    request.domain_targets = vec![DomainTarget {
        host: "blocked.example".to_string(),
        connect_ip: Some("203.0.113.10".to_string()),
        https_port: Some(443),
        http_port: Some(80),
        http_path: "/".to_string(),
        is_control: false,
    }];
    request.quic_targets = vec![QuicTarget {
        host: "blocked.example".to_string(),
        connect_ip: Some("203.0.113.10".to_string()),
        port: 443,
    }];
    let session = MonitorSession::new();

    session.start_scan("session-audit-dns-short".to_string(), request.into()).expect("start automatic audit");
    let report = wait_for_report(&session);
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");
    let audit_assessment = strategy_probe.audit_assessment.as_ref().expect("audit assessment");

    assert_eq!(strategy_probe.completion_kind, StrategyProbeCompletionKind::DnsTamperingWithFallback);
    assert!(!audit_assessment.dns_short_circuited);
    // Confidence may not be Low anymore since strategies actually ran with fallback DNS.
}

#[test]
fn monitor_session_strategy_probe_progress_reports_live_candidate_metadata() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    let mut request = strategy_probe_request(minimal_ui_config());
    request.domain_targets[0].http_port = Some(server.port());
    let session = MonitorSession::new();

    session.start_scan("session-progress".to_string(), request.into()).expect("start strategy probe");

    let mut saw_tcp_live_progress = false;
    let mut saw_quic_live_progress = false;
    let expected_quic_total = crate::candidates::build_quic_candidates_for_suite("quick_v1", &minimal_ui_config())
        .expect("quick_v1 quic candidates")
        .len();

    for _ in 0..300 {
        if let Some(progress_json) = session.poll_progress_json().expect("poll progress json") {
            let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode scan progress");
            match progress.phase.as_str() {
                "tcp" => {
                    if let Some(live_progress) = progress.strategy_probe_progress.as_ref() {
                        saw_tcp_live_progress = true;
                        assert_eq!(live_progress.lane, StrategyProbeProgressLane::Tcp);
                        assert!(live_progress.candidate_index >= 1);
                        assert!(live_progress.candidate_total >= live_progress.candidate_index);
                        assert!(!live_progress.candidate_id.is_empty());
                        assert!(!live_progress.candidate_label.is_empty());
                    }
                }
                "quic" => {
                    if let Some(live_progress) = progress.strategy_probe_progress.as_ref() {
                        saw_quic_live_progress = true;
                        assert_eq!(live_progress.lane, StrategyProbeProgressLane::Quic);
                        assert!(live_progress.candidate_index >= 1);
                        assert_eq!(live_progress.candidate_total, expected_quic_total);
                        assert!(!live_progress.candidate_label.is_empty());
                    }
                }
                "recommendation" => {
                    assert!(progress.strategy_probe_progress.is_none());
                }
                "finished" => {
                    assert!(progress.strategy_probe_progress.is_none());
                    break;
                }
                _ => {}
            }
        }
        thread::sleep(Duration::from_millis(20));
    }

    let report = wait_for_report(&session);
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");
    let executed_quic_candidates = strategy_probe
        .quic_candidates
        .iter()
        .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
        .count();
    let final_progress_json =
        session.poll_progress_json().expect("poll final progress json").expect("final progress json");
    let final_progress: ScanProgress = serde_json::from_str(&final_progress_json).expect("decode final scan progress");

    assert!(saw_tcp_live_progress, "expected TCP live candidate progress");
    if executed_quic_candidates > 0 {
        assert!(saw_quic_live_progress, "expected QUIC live candidate progress");
    }
    assert_eq!(final_progress.phase, "finished");
    assert!(final_progress.strategy_probe_progress.is_none(), "expected finished progress without live metadata");
}

#[test]
fn strategy_probe_report_serializes_normal_completion_kind() {
    let report = crate::types::StrategyProbeReport {
        suite_id: "quick_v1".to_string(),
        tcp_candidates: vec![],
        quic_candidates: vec![],
        recommendation: crate::types::StrategyProbeRecommendation {
            tcp_candidate_id: "tcp-1".to_string(),
            tcp_candidate_label: "TCP baseline".to_string(),
            quic_candidate_id: "quic-1".to_string(),
            quic_candidate_label: "QUIC baseline".to_string(),
            rationale: "Best path".to_string(),
            recommended_proxy_config_json: "{}".to_string(),
        },
        completion_kind: StrategyProbeCompletionKind::Normal,
        audit_assessment: None,
        target_selection: None,
    };

    let json = serde_json::to_string(&report).expect("serialize strategy probe report");

    assert!(json.contains("\"completionKind\":\"NORMAL\""));
}

#[test]
fn monitor_session_drains_passive_events_with_probe_details() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
    let request = ScanRequest {
        profile_id: "default".to_string(),
        display_name: "Passive events".to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: vec![],
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![],
        domain_targets: vec![DomainTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: None,
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        }],
        dns_targets: vec![],
        tcp_targets: vec![],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: None,
    };
    let session = MonitorSession::new();
    session.start_scan("session-1".to_string(), request.into()).expect("start scan");

    let report = wait_for_report(&session);
    assert_eq!(report.outcome_for("domain_reachability"), Some("http_blockpage"));

    let first = session.poll_passive_events_json().expect("poll passive events").expect("events json");
    let events: Vec<NativeSessionEvent> = serde_json::from_str(&first).expect("decode native events");
    assert!(events.is_empty(), "expected probe details to stay in the report, not passive events");

    let second = session.poll_passive_events_json().expect("poll passive events again").expect("events json");
    let drained: Vec<NativeSessionEvent> = serde_json::from_str(&second).expect("decode drained events");
    assert!(drained.is_empty());
}

#[test]
fn monitor_session_allows_restart_after_finished_scan_without_report_cleanup() {
    let request = || ScanRequest {
        profile_id: "default".to_string(),
        display_name: "Restart after finish".to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: vec![],
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![],
        domain_targets: vec![],
        dns_targets: vec![],
        tcp_targets: vec![],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: None,
    };
    let session = MonitorSession::new();
    session.start_scan("session-finished-1".to_string(), request().into()).expect("start first scan");

    let mut finished = false;
    for _ in 0..50 {
        if let Some(progress_json) = session.poll_progress_json().expect("poll progress json") {
            let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode scan progress");
            if progress.is_finished {
                finished = true;
                break;
            }
        }
        thread::sleep(Duration::from_millis(20));
    }
    assert!(finished, "first scan did not finish");

    let mut restarted = false;
    for _ in 0..50 {
        match session.start_scan("session-finished-2".to_string(), request().into()) {
            Ok(()) => {
                restarted = true;
                break;
            }
            Err(err) if err == "diagnostics scan already running" => {
                thread::sleep(Duration::from_millis(20));
            }
            Err(err) => panic!("unexpected restart error: {err}"),
        }
    }

    assert!(restarted, "second scan never started after the first finished");
    let report = wait_for_report(&session);
    assert_eq!(report.summary, "0 completed · 0 healthy");
}

#[test]
fn monitor_json_contracts_match_goldens() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start(move |_request| {
        thread::sleep(Duration::from_millis(60));
        b"HTTP/1.1 403 Forbidden\r\nContent-Length: 35\r\nConnection: close\r\n\r\nAccess denied by upstream filtering"
            .to_vec()
    });
    let request = ScanRequest {
        profile_id: "default".to_string(),
        display_name: "Passive events golden".to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: vec![],
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![],
        domain_targets: vec![DomainTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: None,
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        }],
        dns_targets: vec![],
        tcp_targets: vec![],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: None,
    };
    let session = MonitorSession::new();
    session.start_scan("session-golden".to_string(), request.into()).expect("start scan");

    let progress_json = wait_for_progress_json(&session);
    assert_monitor_json_golden("progress_starting", &progress_json, server.port());

    let report_json = wait_for_report_json(&session);
    assert_monitor_json_golden("final_report", &report_json, server.port());

    let passive_events_json = session.poll_passive_events_json().expect("poll passive events").expect("events json");
    assert_monitor_json_golden("passive_events_first_poll", &passive_events_json, server.port());

    let drained_json = session.poll_passive_events_json().expect("poll passive events again").expect("events json");
    assert_monitor_json_golden("passive_events_second_poll", &drained_json, server.port());
}

#[test]
fn default_runtime_encrypted_dns_context_uses_adguard() {
    let ctx = default_runtime_encrypted_dns_context();
    assert_eq!(ctx.resolver_id.as_deref(), Some("adguard"));
    assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.adguard-dns.com"));
    assert!(ctx.bootstrap_ips.iter().any(|ip| ip == "94.140.14.14"));
}

#[test]
fn default_udp_dns_server_uses_adguard() {
    assert_eq!(DEFAULT_DNS_SERVER, "94.140.14.14:53");
}
