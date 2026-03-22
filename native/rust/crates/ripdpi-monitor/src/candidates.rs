use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{
    ProxyConfigPayload, ProxyEncryptedDnsContext, ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig,
    ProxyUiNumericRange, ProxyUiTcpChainStep, ProxyUiUdpChainStep, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
    ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
};

use crate::dns::encrypted_dns_protocol;
use crate::dns::parse_bootstrap_ips;
use crate::types::{ProbeResult, StrategyProbeCandidateSummary, StrategyProbeRecommendation};
use crate::util::{
    ranged_probe_delay, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL,
    HTTP_FAKE_PROFILE_CLOUDFLARE_GET, STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, STRATEGY_PROBE_SUITE_QUICK_V1,
    TLS_FAKE_PROFILE_GOOGLE_CHROME, UDP_FAKE_PROFILE_DNS_QUERY,
};

#[derive(Clone, Debug)]
pub(crate) struct StrategyCandidateSpec {
    pub(crate) id: &'static str,
    pub(crate) label: &'static str,
    pub(crate) family: &'static str,
    pub(crate) config: ProxyUiConfig,
    pub(crate) notes: Vec<&'static str>,
    pub(crate) preserve_adaptive_fake_ttl: bool,
    pub(crate) warmup: CandidateWarmup,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CandidateWarmup {
    None,
    AdaptiveFakeTtl,
}

#[derive(Debug)]
pub(crate) struct StrategyProbeSuite {
    pub(crate) tcp_candidates: Vec<StrategyCandidateSpec>,
    pub(crate) quic_candidates: Vec<StrategyCandidateSpec>,
    pub(crate) short_circuit_hostfake: bool,
    pub(crate) short_circuit_quic_burst: bool,
}

pub(crate) struct StrategyProbeBaseline {
    pub(crate) failure: ClassifiedFailure,
    pub(crate) results: Vec<ProbeResult>,
}

pub(crate) fn strategy_probe_config_json(config: &ProxyUiConfig) -> String {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: config.clone(),
        runtime_context: None,
    })
    .expect("serialize ui proxy config")
}

pub(crate) fn default_runtime_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("google".to_string()),
        protocol: "doh".to_string(),
        host: DEFAULT_DOH_HOST.to_string(),
        port: DEFAULT_DOH_PORT,
        tls_server_name: Some(DEFAULT_DOH_HOST.to_string()),
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

pub(crate) fn strategy_probe_encrypted_dns_context(
    runtime_context: Option<&ProxyRuntimeContext>,
) -> ProxyEncryptedDnsContext {
    runtime_context.and_then(|value| value.encrypted_dns.clone()).unwrap_or_else(default_runtime_encrypted_dns_context)
}

pub(crate) fn strategy_probe_encrypted_dns_endpoint(
    context: &ProxyEncryptedDnsContext,
) -> Result<EncryptedDnsEndpoint, String> {
    Ok(EncryptedDnsEndpoint {
        protocol: encrypted_dns_protocol(Some(context.protocol.as_str())),
        resolver_id: context.resolver_id.clone(),
        host: context.host.clone(),
        port: context.port,
        tls_server_name: context.tls_server_name.clone(),
        bootstrap_ips: parse_bootstrap_ips(&context.bootstrap_ips)?,
        doh_url: context.doh_url.clone(),
        dnscrypt_provider_name: context.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: context.dnscrypt_public_key.clone(),
    })
}

pub(crate) fn strategy_probe_encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}

pub(crate) fn candidate_pause_ms(seed: u64, candidate: &StrategyCandidateSpec, failed: bool) -> u64 {
    if failed {
        ranged_probe_delay(seed, candidate.id, "candidate_failed", 400, 900)
    } else {
        ranged_probe_delay(seed, candidate.id, "candidate_gap", 120, 350)
    }
}

pub(crate) fn target_probe_pause_ms(seed: u64, candidate: &StrategyCandidateSpec, target_key: &str) -> u64 {
    ranged_probe_delay(seed, candidate.id, target_key, 120, 350)
}

pub(crate) fn build_strategy_probe_suite(suite_id: &str, base: &ProxyUiConfig) -> Result<StrategyProbeSuite, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 => Ok(StrategyProbeSuite {
            tcp_candidates: build_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: true,
            short_circuit_quic_burst: true,
        }),
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(StrategyProbeSuite {
            tcp_candidates: build_full_matrix_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: false,
            short_circuit_quic_burst: false,
        }),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}

pub(crate) fn build_quic_candidates_for_suite(
    suite_id: &str,
    base_tcp: &ProxyUiConfig,
) -> Result<Vec<StrategyCandidateSpec>, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 | STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(build_quic_candidates(base_tcp)),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}

pub(crate) fn build_strategy_probe_summary(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: &StrategyProbeRecommendation,
) -> String {
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return format!(
            "Recommended {} with {}",
            recommendation.tcp_candidate_label, recommendation.quic_candidate_label
        );
    }
    let mut worked = 0usize;
    let mut partial = 0usize;
    let mut failed = 0usize;
    let mut not_applicable = 0usize;
    for candidate in tcp_candidates.iter().chain(quic_candidates.iter()) {
        match candidate.outcome.as_str() {
            "success" => worked += 1,
            "partial" => partial += 1,
            "not_applicable" => not_applicable += 1,
            _ => failed += 1,
        }
    }
    format!(
        "Recommended {} + {}. Worked {} · partial {} · failed {} · not applicable {}",
        recommendation.tcp_candidate_label,
        recommendation.quic_candidate_label,
        worked,
        partial,
        failed,
        not_applicable,
    )
}

pub(crate) fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let baseline = sanitize_current_probe_config(base);
    let parser_only = build_parser_only_candidate(base);
    let parser_unixeol = build_parser_unixeol_candidate(base);
    let parser_methodeol = build_parser_methodeol_candidate(base);
    let split_host = build_split_host_candidate(base);
    let tlsrec_split_host = build_tlsrec_split_host_candidate(base);
    let tlsrec_fake_rich = build_tlsrec_fake_rich_candidate(base);
    let tlsrec_fakedsplit = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    let tlsrec_fakeddisorder = build_tlsrec_fake_approx_candidate(base, "fakeddisorder");
    let tlsrec_hostfake = build_tlsrec_hostfake_candidate(base, false);
    let tlsrec_hostfake_split = build_tlsrec_hostfake_candidate(base, true);

    vec![
        candidate_spec("baseline_current", "Current strategy", "baseline", baseline),
        candidate_spec("parser_only", "Parser-only", "parser", parser_only),
        candidate_spec("parser_unixeol", "Parser + Unix EOL", "parser_aggressive", parser_unixeol),
        candidate_spec("parser_methodeol", "Parser + Method EOL", "parser_aggressive", parser_methodeol),
        candidate_spec("split_host", "Split Host", "split", split_host),
        candidate_spec("tlsrec_split_host", "TLS record + split host", "tlsrec_split", tlsrec_split_host),
        candidate_spec_with_notes(
            "tlsrec_fake_rich",
            "TLS record + rich fake",
            "tlsrec_fake",
            tlsrec_fake_rich,
            vec!["Randomized fake TLS material with original ClientHello framing"],
        ),
        candidate_spec("tlsrec_fakedsplit", "TLS record + fakedsplit", "fake_approx", tlsrec_fakedsplit),
        candidate_spec("tlsrec_fakeddisorder", "TLS record + fakeddisorder", "fake_approx", tlsrec_fakeddisorder),
        candidate_spec("tlsrec_hostfake", "TLS record + hostfake", "hostfake", tlsrec_hostfake),
        candidate_spec_with_notes(
            "tlsrec_hostfake_split",
            "TLS record + hostfake split",
            "hostfake",
            tlsrec_hostfake_split,
            vec!["Adds a follow-up split after hostfake midhost reconstruction"],
        ),
    ]
}

pub(crate) fn build_full_matrix_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_tcp_candidates(base);
    candidates.extend([
        build_activation_window_split_spec(base),
        build_activation_window_hostfake_spec(base),
        build_adaptive_fake_ttl_spec(base),
        build_fake_payload_library_spec(base),
    ]);
    candidates
}

pub(crate) fn build_quic_candidates(base_tcp: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    vec![
        candidate_spec(
            "quic_disabled",
            "QUIC disabled",
            "quic_disabled",
            build_quic_candidate(base_tcp, false, "disabled"),
        ),
        candidate_spec_with_notes(
            "quic_compat_burst",
            "QUIC compat burst",
            "quic_burst",
            build_quic_candidate(base_tcp, true, "compat_default"),
            vec!["Uses Zapret-style compatibility QUIC fake packets"],
        ),
        candidate_spec_with_notes(
            "quic_realistic_burst",
            "QUIC realistic burst",
            "quic_burst",
            build_quic_candidate(base_tcp, true, "realistic_initial"),
            vec!["Uses realistic QUIC Initial packets with the target SNI"],
        ),
    ]
}

pub(crate) fn candidate_spec(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes(id, label, family, config, Vec::new())
}

pub(crate) fn candidate_spec_with_notes(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    StrategyCandidateSpec {
        id,
        label,
        family,
        config,
        notes,
        preserve_adaptive_fake_ttl: false,
        warmup: CandidateWarmup::None,
    }
}

pub(crate) fn sanitize_current_probe_config(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = base.clone();
    config.host_autolearn.enabled = false;
    config.host_autolearn.store_path = None;
    config
}

pub(crate) fn strategy_probe_base(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base);
    config.protocols.desync_http = true;
    config.protocols.desync_https = true;
    config.protocols.desync_udp = false;
    config.chains.tcp_steps.clear();
    config.chains.udp_steps.clear();
    config.fake_packets.fake_ttl = 8;
    config.fake_packets.fake_tls_use_original = false;
    config.fake_packets.fake_tls_randomize = false;
    config.fake_packets.fake_tls_dup_session_id = false;
    config.fake_packets.fake_tls_pad_encap = false;
    config.fake_packets.fake_tls_size = 0;
    config.fake_packets.fake_tls_sni_mode = "fixed".to_string();
    config.fake_packets.drop_sack = false;
    config.fake_packets.fake_offset_marker = "0".to_string();
    config.parser_evasions.host_mixed_case = false;
    config.parser_evasions.domain_mixed_case = false;
    config.parser_evasions.host_remove_spaces = false;
    config.parser_evasions.http_method_eol = false;
    config.parser_evasions.http_unix_eol = false;
    config.quic.fake_profile = "disabled".to_string();
    config.quic.fake_host.clear();
    config
}

pub(crate) fn build_parser_only_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.parser_evasions.host_mixed_case = true;
    config.parser_evasions.domain_mixed_case = true;
    config.parser_evasions.host_remove_spaces = true;
    config
}

pub(crate) fn build_parser_unixeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_unix_eol = true;
    config
}

pub(crate) fn build_parser_methodeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_method_eol = true;
    config
}

pub(crate) fn build_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("split", "host+2")];
    config
}

pub(crate) fn build_tlsrec_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("split", "host+2")];
    config
}

pub(crate) fn build_tlsrec_fake_rich_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("fake", "host+1")];
    config.fake_packets.fake_tls_use_original = true;
    config.fake_packets.fake_tls_randomize = true;
    config.fake_packets.fake_tls_dup_session_id = true;
    config.fake_packets.fake_tls_pad_encap = true;
    config.fake_packets.fake_tls_sni_mode = "randomized".to_string();
    config.fake_packets.fake_offset_marker = "endhost-1".to_string();
    config
}

pub(crate) fn build_tlsrec_fake_approx_candidate(base: &ProxyUiConfig, kind: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step(kind, "host+1")];
    config
}

pub(crate) fn build_tlsrec_hostfake_candidate(base: &ProxyUiConfig, with_split: bool) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    let mut steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: "midsld".to_string(),
            fake_host_template: "googlevideo.com".to_string(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: None,
        },
    ];
    if with_split {
        steps.push(tcp_step("split", "midsld"));
    }
    config.chains.tcp_steps = steps;
    config
}

pub(crate) fn build_quic_candidate(base_tcp: &ProxyUiConfig, enabled: bool, profile: &str) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = enabled;
    config.chains.udp_steps = if enabled {
        vec![ProxyUiUdpChainStep { kind: "fake_burst".to_string(), count: 4, activation_filter: None }]
    } else {
        Vec::new()
    };
    config.quic.fake_profile = profile.to_string();
    config.quic.fake_host.clear();
    config
}

pub(crate) fn build_activation_window_split_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_split_host_candidate(base);
    config.chains.group_activation_filter = Some(default_audit_activation_filter());
    candidate_spec_with_notes(
        "activation_window_split",
        "Activation window + split host",
        "activation_window",
        config,
        vec!["Limits split-host attempts to the first packets in a flow"],
    )
}

pub(crate) fn build_activation_window_hostfake_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_hostfake_candidate(base, false);
    config.chains.group_activation_filter = Some(default_audit_activation_filter());
    candidate_spec_with_notes(
        "activation_window_hostfake",
        "Activation window + hostfake",
        "activation_window",
        config,
        vec!["Applies hostfake only inside a narrow activation window"],
    )
}

pub(crate) fn build_adaptive_fake_ttl_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.adaptive_fake_ttl_enabled = true;
    config.fake_packets.adaptive_fake_ttl_delta = ADAPTIVE_FAKE_TTL_DEFAULT_DELTA;
    config.fake_packets.adaptive_fake_ttl_min = ADAPTIVE_FAKE_TTL_DEFAULT_MIN;
    config.fake_packets.adaptive_fake_ttl_max = ADAPTIVE_FAKE_TTL_DEFAULT_MAX;
    config.fake_packets.adaptive_fake_ttl_fallback = ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK;
    StrategyCandidateSpec {
        id: "adaptive_fake_ttl",
        label: "Adaptive fake TTL",
        family: "adaptive_fake_ttl",
        config,
        notes: vec![
            "Runs an unscored warm-up pass before measured probes",
            "Keeps adaptive fake TTL enabled during candidate execution",
        ],
        preserve_adaptive_fake_ttl: true,
        warmup: CandidateWarmup::AdaptiveFakeTtl,
    }
}

pub(crate) fn build_fake_payload_library_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.http_fake_profile = HTTP_FAKE_PROFILE_CLOUDFLARE_GET.to_string();
    config.fake_packets.tls_fake_profile = TLS_FAKE_PROFILE_GOOGLE_CHROME.to_string();
    config.fake_packets.udp_fake_profile = UDP_FAKE_PROFILE_DNS_QUERY.to_string();
    candidate_spec_with_notes(
        "library_fake_payloads",
        "Library fake payload presets",
        "fake_payload_library",
        config,
        vec!["Uses bundled Cloudflare GET, Chrome TLS, and DNS query fake payload profiles"],
    )
}

pub(crate) fn default_audit_activation_filter() -> ProxyUiActivationFilter {
    ProxyUiActivationFilter {
        round: Some(ProxyUiNumericRange { start: Some(1), end: Some(2) }),
        payload_size: Some(ProxyUiNumericRange { start: Some(64), end: Some(512) }),
        stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(2047) }),
    }
}

pub(crate) fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal_ui_config() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.protocols.desync_udp = true;
        config.chains.tcp_steps = vec![tcp_step("disorder", "host+1")];
        config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
        config
    }

    #[test]
    fn build_strategy_probe_suite_unknown_id_returns_error() {
        let base = minimal_ui_config();
        let result = build_strategy_probe_suite("nonexistent_v99", &base);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Unsupported"));
    }

    #[test]
    fn build_strategy_probe_suite_quick_v1_returns_candidates() {
        let base = minimal_ui_config();
        let suite = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1 suite");
        assert!(!suite.tcp_candidates.is_empty());
        assert!(!suite.quic_candidates.is_empty());
        assert!(suite.short_circuit_hostfake);
        assert!(suite.short_circuit_quic_burst);
    }

    #[test]
    fn build_strategy_probe_suite_full_matrix_has_extra_candidates() {
        let base = minimal_ui_config();
        let quick = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1");
        let full = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1");
        assert!(full.tcp_candidates.len() > quick.tcp_candidates.len());
        assert!(!full.short_circuit_hostfake);
        assert!(!full.short_circuit_quic_burst);
    }

    #[test]
    fn default_runtime_encrypted_dns_context_returns_google_doh() {
        let ctx = default_runtime_encrypted_dns_context();
        assert_eq!(ctx.protocol, "doh");
        assert_eq!(ctx.host, "dns.google");
        assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.google"));
        assert!(!ctx.bootstrap_ips.is_empty());
    }

    #[test]
    fn strategy_probe_encrypted_dns_label_uses_doh_url_when_present() {
        let ctx = default_runtime_encrypted_dns_context();
        let label = strategy_probe_encrypted_dns_label(&ctx);
        assert!(label.contains("dns.google"));
    }

    #[test]
    fn strategy_probe_encrypted_dns_label_falls_back_to_host_port() {
        let ctx = ProxyEncryptedDnsContext {
            resolver_id: None,
            protocol: "dot".to_string(),
            host: "example.com".to_string(),
            port: 853,
            tls_server_name: None,
            bootstrap_ips: Vec::new(),
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let label = strategy_probe_encrypted_dns_label(&ctx);
        assert_eq!(label, "example.com:853");
    }

    #[test]
    fn candidate_pause_ms_failed_is_larger() {
        let spec = candidate_spec("test", "Test", "test", minimal_ui_config());
        let ok_pause = candidate_pause_ms(42, &spec, false);
        let fail_pause = candidate_pause_ms(42, &spec, true);
        assert!(fail_pause > ok_pause, "failed pause {fail_pause} should exceed ok pause {ok_pause}");
    }

    #[test]
    fn sanitize_current_probe_config_disables_autolearn() {
        let mut base = minimal_ui_config();
        base.host_autolearn.enabled = true;
        base.host_autolearn.store_path = Some("/tmp/test".to_string());
        let sanitized = sanitize_current_probe_config(&base);
        assert!(!sanitized.host_autolearn.enabled);
        assert!(sanitized.host_autolearn.store_path.is_none());
    }

    #[test]
    fn strategy_probe_base_resets_desync_fields() {
        let mut base = minimal_ui_config();
        base.chains.tcp_steps = vec![tcp_step("fake", "host+1")];
        let probe = strategy_probe_base(&base);
        assert!(probe.chains.tcp_steps.is_empty());
        assert!(probe.protocols.desync_http);
        assert!(probe.protocols.desync_https);
        assert!(!probe.protocols.desync_udp);
    }

    #[test]
    fn build_quic_candidates_for_suite_unknown_id_returns_error() {
        let base = minimal_ui_config();
        let result = build_quic_candidates_for_suite("nonexistent_v99", &base);
        assert!(result.is_err());
    }

}
