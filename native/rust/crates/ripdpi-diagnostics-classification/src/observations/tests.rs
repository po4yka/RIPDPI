use super::common::{
    dns_status, endpoint_status, http_status, quic_status, strategy_status, tcp_status, telegram_transfer_status,
    telegram_verdict, throughput_status, tls_status,
};
use super::*;
use crate::types::{
    DnsObservationStatus, EndpointProbeStatus, HttpProbeStatus, ObservationKind, ProbeDetail, QuicProbeStatus,
    StrategyProbeProtocol, StrategyProbeStatus, TcpProbeStatus, TelegramTransferStatus, TelegramVerdict,
    TelegramWsTunnelStatus, ThroughputProbeStatus, TlsProbeStatus, TransportFailureKind,
};

#[test]
fn throughput_probe_builds_typed_observation() {
    let observation = observation_for_probe(&ProbeResult {
        probe_type: "throughput_window".to_string(),
        target: "YouTube".to_string(),
        outcome: "throughput_measured".to_string(),
        details: vec![
            ProbeDetail { key: "isControl".to_string(), value: "false".to_string() },
            ProbeDetail { key: "medianBps".to_string(), value: "1000".to_string() },
            ProbeDetail { key: "bpsReadings".to_string(), value: "1000|800".to_string() },
            ProbeDetail { key: "windowBytes".to_string(), value: "8192".to_string() },
        ],
    })
    .expect("observation");

    let throughput = observation.throughput.expect("throughput payload");
    assert_eq!(observation.kind, ObservationKind::Throughput);
    assert_eq!(throughput.status, ThroughputProbeStatus::Measured);
    assert_eq!(throughput.sample_bps, vec![1000, 800]);
    assert_eq!(throughput.window_bytes, 8192);
}

#[test]
fn transport_failure_parses_timeout_reset_and_close() {
    assert_eq!(transport_failure("operation timed out"), TransportFailureKind::Timeout);
    assert_eq!(transport_failure("connection reset by peer"), TransportFailureKind::Reset);
    assert_eq!(transport_failure("unexpected eof"), TransportFailureKind::Close);
}

// ── Helper ───────────────────────────────────────────────────────────

fn probe(probe_type: &str, target: &str, outcome: &str, details: &[(&str, &str)]) -> ProbeResult {
    ProbeResult {
        probe_type: probe_type.to_string(),
        target: target.to_string(),
        outcome: outcome.to_string(),
        details: details.iter().map(|(k, v)| ProbeDetail { key: k.to_string(), value: v.to_string() }).collect(),
    }
}

// ── Status mapping tests ─────────────────────────────────────────────

#[test]
fn dns_status_maps_all_variants() {
    assert_eq!(dns_status("dns_match"), DnsObservationStatus::Match);
    assert_eq!(dns_status("dns_expected_mismatch"), DnsObservationStatus::ExpectedMismatch);
    assert_eq!(dns_status("dns_compatible_divergence"), DnsObservationStatus::CompatibleDivergence);
    assert_eq!(dns_status("dns_suspicious_divergence"), DnsObservationStatus::SuspiciousDivergence);
    assert_eq!(dns_status("dns_sinkhole_substitution"), DnsObservationStatus::SinkholeSubstitution);
    assert_eq!(dns_status("dns_nxdomain_mismatch"), DnsObservationStatus::NxdomainMismatch);
    assert_eq!(dns_status("dns_oracle_unavailable"), DnsObservationStatus::OracleUnavailable);
    assert_eq!(dns_status("udp_blocked"), DnsObservationStatus::UdpBlocked);
    assert_eq!(dns_status("udp_skipped_or_blocked"), DnsObservationStatus::UdpBlocked);
    assert_eq!(dns_status("unknown"), DnsObservationStatus::Unavailable);
}

#[test]
fn http_status_maps_all_variants() {
    assert_eq!(http_status(Some("http_ok")), HttpProbeStatus::Ok);
    assert_eq!(http_status(Some("http_status_200")), HttpProbeStatus::Ok);
    assert_eq!(http_status(Some("http_status_301")), HttpProbeStatus::Ok);
    assert_eq!(http_status(Some("http_status_308")), HttpProbeStatus::Ok);
    assert_eq!(http_status(Some("http_status_400")), HttpProbeStatus::Unreachable);
    assert_eq!(http_status(Some("http_status_500")), HttpProbeStatus::Unreachable);
    assert_eq!(http_status(Some("http_blockpage")), HttpProbeStatus::Blockpage);
    assert_eq!(http_status(Some("not_run")), HttpProbeStatus::NotRun);
    assert_eq!(http_status(None), HttpProbeStatus::NotRun);
    assert_eq!(http_status(Some("unknown")), HttpProbeStatus::Unreachable);
}

#[test]
fn tls_status_maps_all_variants() {
    assert_eq!(tls_status(Some("tls_ok")), TlsProbeStatus::Ok);
    assert_eq!(tls_status(Some("tls_version_split")), TlsProbeStatus::VersionSplit);
    assert_eq!(tls_status(Some("tls_cert_invalid")), TlsProbeStatus::CertInvalid);
    assert_eq!(tls_status(Some("not_run")), TlsProbeStatus::NotRun);
    assert_eq!(tls_status(None), TlsProbeStatus::NotRun);
    assert_eq!(tls_status(Some("unknown")), TlsProbeStatus::HandshakeFailed);
}

#[test]
fn tcp_status_maps_all_variants() {
    assert_eq!(tcp_status("whitelist_sni_ok"), TcpProbeStatus::WhitelistSniOk);
    assert_eq!(tcp_status("tcp_16kb_blocked"), TcpProbeStatus::Blocked16Kb);
    assert_eq!(tcp_status("tcp_freeze_after_threshold"), TcpProbeStatus::FreezeAfterThreshold);
    assert_eq!(tcp_status("tcp_ok"), TcpProbeStatus::Ok);
    assert_eq!(tcp_status("fat_ok"), TcpProbeStatus::Ok);
    assert_eq!(tcp_status("tcp_connect_failed"), TcpProbeStatus::ConnectFailed);
    assert_eq!(tcp_status("unknown"), TcpProbeStatus::Error);
}

#[test]
fn quic_status_maps_all_variants() {
    assert_eq!(quic_status("quic_initial_response"), QuicProbeStatus::InitialResponse);
    assert_eq!(quic_status("quic_response"), QuicProbeStatus::Response);
    assert_eq!(quic_status("quic_empty"), QuicProbeStatus::Empty);
    assert_eq!(quic_status("not_run"), QuicProbeStatus::NotRun);
    assert_eq!(quic_status("unknown"), QuicProbeStatus::Error);
}

#[test]
fn endpoint_status_maps_all_variants() {
    assert_eq!(endpoint_status(Some("tls_ok")), EndpointProbeStatus::Ok);
    assert_eq!(endpoint_status(Some("tcp_connect_ok")), EndpointProbeStatus::Ok);
    assert_eq!(endpoint_status(Some("not_run")), EndpointProbeStatus::NotRun);
    assert_eq!(endpoint_status(None), EndpointProbeStatus::NotRun);
    assert_eq!(endpoint_status(Some("http_blocked")), EndpointProbeStatus::Blocked);
    assert_eq!(endpoint_status(Some("tls_blocked")), EndpointProbeStatus::Blocked);
    assert_eq!(endpoint_status(Some("unknown")), EndpointProbeStatus::Failed);
}

#[test]
fn telegram_verdict_maps_all_variants() {
    assert_eq!(telegram_verdict("ok"), TelegramVerdict::Ok);
    assert_eq!(telegram_verdict("slow"), TelegramVerdict::Slow);
    assert_eq!(telegram_verdict("partial"), TelegramVerdict::Partial);
    assert_eq!(telegram_verdict("blocked"), TelegramVerdict::Blocked);
    assert_eq!(telegram_verdict("unknown"), TelegramVerdict::Error);
}

#[test]
fn telegram_transfer_status_maps_all_variants() {
    assert_eq!(telegram_transfer_status("ok"), TelegramTransferStatus::Ok);
    assert_eq!(telegram_transfer_status("slow"), TelegramTransferStatus::Slow);
    assert_eq!(telegram_transfer_status("stalled"), TelegramTransferStatus::Stalled);
    assert_eq!(telegram_transfer_status("blocked"), TelegramTransferStatus::Blocked);
    assert_eq!(telegram_transfer_status("unknown"), TelegramTransferStatus::Error);
}

#[test]
fn throughput_status_maps_all_variants() {
    assert_eq!(throughput_status("throughput_measured"), ThroughputProbeStatus::Measured);
    assert_eq!(throughput_status("invalid_target"), ThroughputProbeStatus::InvalidTarget);
    assert_eq!(throughput_status("unknown"), ThroughputProbeStatus::HttpUnreachable);
}

#[test]
fn strategy_status_maps_all_variants() {
    assert_eq!(strategy_status("http_ok"), StrategyProbeStatus::Success);
    assert_eq!(strategy_status("http_redirect"), StrategyProbeStatus::Success);
    assert_eq!(strategy_status("tls_ok"), StrategyProbeStatus::Success);
    assert_eq!(strategy_status("tls_version_split"), StrategyProbeStatus::Partial);
    assert_eq!(strategy_status("quic_initial_response"), StrategyProbeStatus::Success);
    assert_eq!(strategy_status("quic_response"), StrategyProbeStatus::Success);
    assert_eq!(strategy_status("tls_ech_only"), StrategyProbeStatus::Partial);
    assert_eq!(strategy_status("partial"), StrategyProbeStatus::Partial);
    assert_eq!(strategy_status("skipped"), StrategyProbeStatus::Skipped);
    assert_eq!(strategy_status("not_applicable"), StrategyProbeStatus::NotApplicable);
    assert_eq!(strategy_status("unknown"), StrategyProbeStatus::Failed);
}

// ── transport_failure expanded ───────────────────────────────────────

#[test]
fn transport_failure_classifies_all_categories() {
    assert_eq!(transport_failure("TLS alert received"), TransportFailureKind::Alert);
    assert_eq!(transport_failure("broken pipe"), TransportFailureKind::Reset);
    assert_eq!(transport_failure("connection aborted"), TransportFailureKind::Reset);
    assert_eq!(transport_failure("received close notify"), TransportFailureKind::Close);
    assert_eq!(transport_failure("connection closed"), TransportFailureKind::Close);
    assert_eq!(transport_failure("would block"), TransportFailureKind::Timeout);
    assert_eq!(transport_failure("timeout exceeded"), TransportFailureKind::Timeout);
    assert_eq!(transport_failure("bad issuer"), TransportFailureKind::Certificate);
    assert_eq!(transport_failure("invalid certificate"), TransportFailureKind::Certificate);
    assert_eq!(transport_failure("some other error"), TransportFailureKind::Other);
    assert_eq!(transport_failure(""), TransportFailureKind::None);
    assert_eq!(transport_failure("none"), TransportFailureKind::None);
    assert_eq!(transport_failure("not_run"), TransportFailureKind::None);
}

// ── Utility function tests ──────────────────────────────────────────

#[test]
fn detail_value_finds_matching_key() {
    let result = probe("test", "t", "ok", &[("key1", "val1"), ("key2", "val2")]);
    assert_eq!(detail_value(&result, "key1"), Some("val1"));
    assert_eq!(detail_value(&result, "key2"), Some("val2"));
}

#[test]
fn detail_value_returns_none_for_missing() {
    let result = probe("test", "t", "ok", &[("key1", "val1")]);
    assert_eq!(detail_value(&result, "missing"), None);
}

#[test]
fn detail_list_splits_pipe_and_comma() {
    let result = probe("test", "t", "ok", &[("addrs", "1.2.3.4|5.6.7.8,9.10.11.12")]);
    assert_eq!(detail_list(&result, "addrs"), vec!["1.2.3.4", "5.6.7.8", "9.10.11.12"]);
}

#[test]
fn detail_list_filters_empty_none_unknown() {
    let result = probe("test", "t", "ok", &[("addrs", "1.2.3.4|none||unknown|5.6.7.8")]);
    assert_eq!(detail_list(&result, "addrs"), vec!["1.2.3.4", "5.6.7.8"]);
}

#[test]
fn detail_list_returns_empty_for_missing_key() {
    let result = probe("test", "t", "ok", &[]);
    assert!(detail_list(&result, "missing").is_empty());
}

#[test]
fn observations_for_results_filters_unknown_types() {
    let results = vec![
        probe("dns_integrity", "example.com", "dns_match", &[]),
        probe("unknown_type", "target", "ok", &[]),
        probe("tcp_fat_header", "provider", "tcp_ok", &[]),
    ];
    let observations = observations_for_results(&results);
    assert_eq!(observations.len(), 2);
    assert_eq!(observations[0].kind, ObservationKind::Dns);
    assert_eq!(observations[1].kind, ObservationKind::Tcp);
}

// ── observation_for_probe integration tests ─────────────────────────

#[test]
fn dns_observation_builds_from_probe() {
    let result = probe(
        "dns_integrity",
        "example.com",
        "dns_sinkhole_substitution",
        &[("udpAddresses", "1.2.3.4|5.6.7.8"), ("encryptedAddresses", "9.10.11.12"), ("udpLatencyMs", "42")],
    );
    let obs = observation_for_probe(&result).expect("dns observation");
    assert_eq!(obs.kind, ObservationKind::Dns);
    assert_eq!(obs.target, "example.com");
    let dns = obs.dns.expect("dns payload");
    assert_eq!(dns.status, DnsObservationStatus::SinkholeSubstitution);
    assert_eq!(dns.udp_addresses, vec!["1.2.3.4", "5.6.7.8"]);
    assert_eq!(dns.encrypted_addresses, vec!["9.10.11.12"]);
    assert_eq!(dns.udp_latency_ms, Some(42));
}

#[test]
fn dns_observation_falls_back_to_doh_addresses() {
    let result = probe("dns_integrity", "example.com", "dns_match", &[("dohAddresses", "1.1.1.1")]);
    let dns = observation_for_probe(&result).expect("observation").dns.expect("dns");
    assert_eq!(dns.encrypted_addresses, vec!["1.1.1.1"]);
}

#[test]
fn domain_observation_builds_from_probe() {
    let result = probe(
        "domain_reachability",
        "example.com",
        "tls_ok",
        &[
            ("httpStatus", "http_ok"),
            ("tls13Status", "tls_ok"),
            ("tls12Status", "tls_version_split"),
            ("isControl", "true"),
            ("h3Advertised", "true"),
        ],
    );
    let obs = observation_for_probe(&result).expect("domain observation");
    assert_eq!(obs.kind, ObservationKind::Domain);
    let domain = obs.domain.expect("domain payload");
    assert_eq!(domain.http_status, HttpProbeStatus::Ok);
    assert_eq!(domain.tls13_status, TlsProbeStatus::Ok);
    assert_eq!(domain.tls12_status, TlsProbeStatus::VersionSplit);
    assert!(domain.is_control);
    assert!(domain.h3_advertised);
}

#[test]
fn domain_observation_detects_certificate_anomaly() {
    let result = probe("domain_reachability", "example.com", "tls_cert_invalid", &[]);
    let domain = observation_for_probe(&result).expect("obs").domain.expect("domain");
    assert!(domain.certificate_anomaly);
}

#[test]
fn tcp_observation_builds_from_probe() {
    let result = probe(
        "tcp_fat_header",
        "provider.com",
        "tcp_16kb_blocked",
        &[("provider", "TestProvider"), ("bytesSent", "1024"), ("responsesSeen", "3"), ("selectedSni", "test.sni")],
    );
    let obs = observation_for_probe(&result).expect("tcp observation");
    assert_eq!(obs.kind, ObservationKind::Tcp);
    let tcp = obs.tcp.expect("tcp payload");
    assert_eq!(tcp.provider, "TestProvider");
    assert_eq!(tcp.status, TcpProbeStatus::Blocked16Kb);
    assert_eq!(tcp.bytes_sent, Some(1024));
    assert_eq!(tcp.responses_seen, Some(3));
    assert_eq!(tcp.selected_sni, Some("test.sni".to_string()));
}

#[test]
fn tcp_observation_freeze_after_threshold() {
    let result = probe(
        "tcp_fat_header",
        "provider.com",
        "tcp_freeze_after_threshold",
        &[
            ("provider", "TestProvider"),
            ("bytesSent", "16384"),
            ("responsesSeen", "1"),
            ("freezeThresholdBytes", "16384"),
        ],
    );
    let obs = observation_for_probe(&result).expect("tcp observation");
    let tcp = obs.tcp.expect("tcp payload");
    assert_eq!(tcp.status, TcpProbeStatus::FreezeAfterThreshold);
    assert_eq!(tcp.freeze_threshold_bytes, Some(16384));
    assert_eq!(tcp.bytes_sent, Some(16384));
    assert_eq!(tcp.responses_seen, Some(1));
}

#[test]
fn tcp_observation_includes_port_and_alt_port() {
    let result = probe(
        "tcp_fat_header",
        "1.1.1.1:443 (Cloudflare)",
        "tcp_freeze_after_threshold",
        &[
            ("provider", "Cloudflare"),
            ("bytesSent", "16384"),
            ("responsesSeen", "1"),
            ("port", "443"),
            ("altPort", "8443"),
            ("altPortStatus", "ok"),
        ],
    );
    let obs = observation_for_probe(&result).expect("tcp observation");
    let tcp = obs.tcp.expect("tcp payload");
    assert_eq!(tcp.port, Some(443));
    assert_eq!(tcp.alt_port, Some(8443));
    assert_eq!(tcp.alt_port_status.as_deref(), Some("ok"));
}

#[test]
fn quic_observation_builds_from_probe() {
    let result = probe(
        "quic_reachability",
        "quic.example.com",
        "quic_ok",
        &[("status", "quic_initial_response"), ("error", "none")],
    );
    let obs = observation_for_probe(&result).expect("quic observation");
    assert_eq!(obs.kind, ObservationKind::Quic);
    let quic = obs.quic.expect("quic payload");
    assert_eq!(quic.status, QuicProbeStatus::InitialResponse);
    assert_eq!(quic.transport_failure, TransportFailureKind::None);
}

#[test]
fn service_observation_builds_from_probe() {
    let result = probe(
        "service_reachability",
        "Telegram",
        "service_ok",
        &[
            ("service", "Telegram"),
            ("bootstrapStatus", "http_ok"),
            ("mediaStatus", "http_blockpage"),
            ("gatewayStatus", "tls_ok"),
            ("gatewayError", "none"),
            ("quicStatus", "not_run"),
            ("quicError", "none"),
        ],
    );
    let obs = observation_for_probe(&result).expect("service observation");
    assert_eq!(obs.kind, ObservationKind::Service);
    let svc = obs.service.expect("service payload");
    assert_eq!(svc.service, "Telegram");
    assert_eq!(svc.bootstrap_status, HttpProbeStatus::Ok);
    assert_eq!(svc.media_status, HttpProbeStatus::Blockpage);
    assert_eq!(svc.endpoint_status, EndpointProbeStatus::Ok);
}

#[test]
fn circumvention_observation_builds_from_probe() {
    let result = probe(
        "circumvention_reachability",
        "Psiphon",
        "circumvention_ok",
        &[
            ("tool", "Psiphon"),
            ("bootstrapStatus", "http_ok"),
            ("handshakeStatus", "tls_ok"),
            ("handshakeError", "none"),
        ],
    );
    let obs = observation_for_probe(&result).expect("circumvention observation");
    assert_eq!(obs.kind, ObservationKind::Circumvention);
    let circ = obs.circumvention.expect("circumvention payload");
    assert_eq!(circ.tool, "Psiphon");
    assert_eq!(circ.bootstrap_status, HttpProbeStatus::Ok);
    assert_eq!(circ.handshake_status, EndpointProbeStatus::Ok);
    assert_eq!(circ.handshake_failure, TransportFailureKind::None);
}

#[test]
fn telegram_observation_builds_from_probe() {
    let result = probe(
        "telegram_availability",
        "Telegram",
        "telegram_ok",
        &[
            ("verdict", "ok"),
            ("qualityScore", "85"),
            ("downloadStatus", "ok"),
            ("uploadStatus", "slow"),
            ("dcReachable", "3"),
            ("dcTotal", "5"),
            ("downloadAvgBps", "120000"),
            ("downloadPeakBps", "180000"),
            ("uploadAvgBps", "64000"),
            ("uploadPeakBps", "96000"),
            ("dcResults", "DC1:ok:42ms|DC2:fail|DC3:ok:50ms"),
            ("wsTunnelStatus", "unreachable"),
            ("wsTunnelRttMs", "250"),
            ("wsTunnelError", "tls handshake failed"),
        ],
    );
    let obs = observation_for_probe(&result).expect("telegram observation");
    assert_eq!(obs.kind, ObservationKind::Telegram);
    let tg = obs.telegram.expect("telegram payload");
    assert_eq!(tg.verdict, TelegramVerdict::Ok);
    assert_eq!(tg.quality_score, 85);
    assert_eq!(tg.download_status, TelegramTransferStatus::Ok);
    assert_eq!(tg.upload_status, TelegramTransferStatus::Slow);
    assert_eq!(tg.dc_reachable, 3);
    assert_eq!(tg.dc_total, 5);
    assert_eq!(tg.download_avg_bps, 120000);
    assert_eq!(tg.download_peak_bps, 180000);
    assert_eq!(tg.upload_avg_bps, 64000);
    assert_eq!(tg.upload_peak_bps, 96000);
    assert_eq!(tg.dc_results, vec!["DC1:ok:42ms", "DC2:fail", "DC3:ok:50ms"]);
    assert_eq!(tg.ws_tunnel_status, TelegramWsTunnelStatus::Unreachable);
    assert_eq!(tg.ws_tunnel_rtt_ms, 250);
    assert_eq!(tg.ws_tunnel_error.as_deref(), Some("tls handshake failed"));
}

#[test]
fn strategy_http_observation_builds_from_probe() {
    let result = probe(
        "strategy_http",
        "example.com",
        "http_ok",
        &[("candidateId", "split_1"), ("candidateLabel", "Split"), ("candidateFamily", "split")],
    );
    let obs = observation_for_probe(&result).expect("strategy observation");
    assert_eq!(obs.kind, ObservationKind::Strategy);
    let strat = obs.strategy.expect("strategy payload");
    assert_eq!(strat.protocol, StrategyProbeProtocol::Http);
    assert_eq!(strat.status, StrategyProbeStatus::Success);
    assert_eq!(strat.candidate_id, Some("split_1".to_string()));
}

#[test]
fn strategy_https_observation_includes_tls_error() {
    let result =
        probe("strategy_https", "example.com", "tls_handshake_failed", &[("tlsError", "certificate verify failed")]);
    let strat = observation_for_probe(&result).expect("obs").strategy.expect("strategy");
    assert_eq!(strat.protocol, StrategyProbeProtocol::Https);
    assert_eq!(strat.status, StrategyProbeStatus::Failed);
    assert_eq!(strat.tls_error, Some("certificate verify failed".to_string()));
    assert_eq!(strat.transport_failure, TransportFailureKind::Certificate);
}

#[test]
fn strategy_quic_observation_builds_from_probe() {
    let result = probe("strategy_quic", "example.com", "quic_response", &[]);
    let strat = observation_for_probe(&result).expect("obs").strategy.expect("strategy");
    assert_eq!(strat.protocol, StrategyProbeProtocol::Quic);
    assert_eq!(strat.status, StrategyProbeStatus::Success);
}

#[test]
fn unknown_probe_type_returns_none() {
    let result = probe("unknown_type", "target", "ok", &[]);
    assert!(observation_for_probe(&result).is_none());
}

// ── Injection profiling tests ───────────────────────────────────────

#[test]
fn dns_observation_populates_injection_fields() {
    let result = probe(
        "dns_integrity",
        "example.com",
        "dns_sinkhole_substitution",
        &[("injectionLatencyRatio", "4000"), ("forgedAddresses", "1.2.3.4,5.6.7.8")],
    );
    let dns = observation_for_probe(&result).expect("obs").dns.expect("dns");
    assert_eq!(dns.injection_latency_ratio, Some(4000));
    assert_eq!(dns.forged_addresses, Some(vec!["1.2.3.4".to_string(), "5.6.7.8".to_string()]));
    assert!(dns.forged_address_pool.is_none());
}

#[test]
fn dns_observation_skips_injection_fields_for_match() {
    let result = probe("dns_integrity", "example.com", "dns_match", &[]);
    let dns = observation_for_probe(&result).expect("obs").dns.expect("dns");
    assert!(dns.injection_latency_ratio.is_none());
    assert!(dns.forged_addresses.is_none());
}

#[test]
fn dns_injection_pool_detected_for_three_plus_domains() {
    let results = vec![
        probe("dns_integrity", "a.com", "dns_sinkhole_substitution", &[("forgedAddresses", "10.0.0.1")]),
        probe("dns_integrity", "b.com", "dns_sinkhole_substitution", &[("forgedAddresses", "10.0.0.1")]),
        probe("dns_integrity", "c.com", "dns_sinkhole_substitution", &[("forgedAddresses", "10.0.0.1")]),
    ];
    let observations = observations_for_results(&results);
    assert_eq!(observations.len(), 3);
    for obs in &observations {
        let dns = obs.dns.as_ref().expect("dns");
        assert_eq!(dns.forged_address_pool, Some("10.0.0.1".to_string()));
        assert!(obs.evidence.contains(&"dns_injection_pool_detected".to_string()));
    }
}

#[test]
fn dns_injection_pool_not_detected_for_two_domains() {
    let results = vec![
        probe("dns_integrity", "a.com", "dns_sinkhole_substitution", &[("forgedAddresses", "10.0.0.1")]),
        probe("dns_integrity", "b.com", "dns_sinkhole_substitution", &[("forgedAddresses", "10.0.0.1")]),
    ];
    let observations = observations_for_results(&results);
    for obs in &observations {
        let dns = obs.dns.as_ref().expect("dns");
        assert!(dns.forged_address_pool.is_none());
        assert!(!obs.evidence.contains(&"dns_injection_pool_detected".to_string()));
    }
}
