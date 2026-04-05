use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{
    ProxyConfigPayload, ProxyEncryptedDnsContext, ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig,
    ProxyUiNumericRange, ProxyUiTcpChainStep, ProxyUiUdpChainStep, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
    ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
};

use crate::dns::encrypted_dns_protocol;
use crate::dns::parse_bootstrap_ips;
use crate::types::{
    ProbeResult, StrategyProbeAuditAssessment, StrategyProbeAuditConfidenceLevel, StrategyProbeCandidateSummary,
    StrategyProbeRecommendation,
};
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
    pub(crate) eligibility: CandidateEligibility,
    pub(crate) config: ProxyUiConfig,
    pub(crate) notes: Vec<&'static str>,
    pub(crate) preserve_adaptive_fake_ttl: bool,
    pub(crate) warmup: CandidateWarmup,
    /// When `true`, this candidate requires the ability to set a custom IP TTL
    /// on outgoing sockets (via `setsockopt(IP_TTL)`). On Android VPN/tun mode
    /// this syscall fails, so candidates that depend on TTL manipulation (fake,
    /// fakedsplit, fakeddisorder, hostfake without a follow-up split) must be
    /// skipped when TTL capability is unavailable.
    pub(crate) requires_fake_ttl: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CandidateEligibility {
    Always,
    RequiresEchCapability,
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
    pub(crate) family_failure_threshold: usize,
}

pub(crate) struct StrategyProbeBaseline {
    pub(crate) failure: ClassifiedFailure,
    pub(crate) results: Vec<ProbeResult>,
    /// Per-host encrypted-DNS-resolved IPs for targets where DNS tampering was confirmed.
    pub(crate) encrypted_ip_overrides: Vec<(String, std::net::IpAddr)>,
}

pub(crate) fn strategy_probe_config_json(config: &ProxyUiConfig) -> String {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: config.clone(),
        runtime_context: None,
        log_context: None,
    })
    .expect("serialize ui proxy config")
}

/// Returns the hardcoded Cloudflare DoH configuration used as fallback when no
/// user-supplied runtime encrypted DNS context is available.
///
/// Strategy probes should ideally resolve DNS through the same resolver that
/// runtime traffic uses. When a user has configured a different resolver (e.g.
/// Google DoH, Quad9), falling back to AdGuard here means probes may observe
/// different DNS behavior than actual connections. See
/// [`strategy_probe_encrypted_dns_context`] for the precedence logic.
pub(crate) fn default_runtime_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("adguard".to_string()),
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

/// Resolves the encrypted DNS context for strategy probes.
///
/// Precedence: user-supplied runtime context > Cloudflare DoH default.
/// When the runtime context is absent or has no `encrypted_dns` field, the
/// Cloudflare fallback from [`default_runtime_encrypted_dns_context`] is used
/// and a debug log is emitted so operators can correlate any DNS-path mismatch.
pub(crate) fn strategy_probe_encrypted_dns_context(
    runtime_context: Option<&ProxyRuntimeContext>,
) -> ProxyEncryptedDnsContext {
    match runtime_context.and_then(|value| value.encrypted_dns.clone()) {
        Some(ctx) => ctx,
        None => {
            tracing::debug!(
                "no runtime encrypted DNS context provided; falling back to default Cloudflare DoH for strategy probes"
            );
            default_runtime_encrypted_dns_context()
        }
    }
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
            family_failure_threshold: 2,
        }),
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(StrategyProbeSuite {
            tcp_candidates: build_full_matrix_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: false,
            short_circuit_quic_burst: false,
            family_failure_threshold: 4,
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
    audit_assessment: Option<&StrategyProbeAuditAssessment>,
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
    let mut summary = format!(
        "Recommended {} + {}. Worked {} · partial {} · failed {} · not applicable {}",
        recommendation.tcp_candidate_label,
        recommendation.quic_candidate_label,
        worked,
        partial,
        failed,
        not_applicable,
    );
    if let Some(assessment) = audit_assessment {
        summary.push_str(&format!(
            " · confidence {} · matrix coverage {}%",
            strategy_probe_audit_confidence_label(assessment.confidence.level),
            assessment.coverage.matrix_coverage_percent,
        ));
    }
    summary
}

fn strategy_probe_audit_confidence_label(level: StrategyProbeAuditConfidenceLevel) -> &'static str {
    match level {
        StrategyProbeAuditConfidenceLevel::High => "HIGH",
        StrategyProbeAuditConfidenceLevel::Medium => "MEDIUM",
        StrategyProbeAuditConfidenceLevel::Low => "LOW",
    }
}

pub(crate) fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let baseline = sanitize_current_probe_config(base);
    let parser_only = build_parser_only_candidate(base);
    let parser_unixeol = build_parser_unixeol_candidate(base);
    let parser_methodeol = build_parser_methodeol_candidate(base);
    let split_host = build_split_host_candidate(base);
    let disorder_host = build_disorder_host_candidate(base);
    let tlsrec_disorder = build_tlsrec_disorder_candidate(base);
    let ech_split = build_ech_split_candidate(base);
    let ech_tlsrec = build_ech_tlsrec_candidate(base);
    let tlsrec_split_host = build_tlsrec_split_host_candidate(base);
    let tlsrec_fake_rich = build_tlsrec_fake_rich_candidate(base);
    let tlsrec_fakedsplit = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    let tlsrec_fakeddisorder = build_tlsrec_fake_approx_candidate(base, "fakeddisorder");
    let tlsrec_hostfake = build_tlsrec_hostfake_candidate(base, false);
    let tlsrec_hostfake_split = build_tlsrec_hostfake_candidate(base, true);
    let ipfrag_capable = supports_tcp_ip_fragmentation();

    let mut candidates = vec![
        candidate_spec("baseline_current", "Current strategy", "baseline", baseline),
        candidate_spec("tlsrec_split_host", "TLS record + split host", "tlsrec_split", tlsrec_split_host),
        candidate_spec_with_notes(
            "tlsrec_hostfake_split",
            "TLS record + hostfake split",
            "hostfake",
            tlsrec_hostfake_split,
            vec!["Adds a follow-up split after hostfake midhost reconstruction"],
        ),
        candidate_spec_with_notes(
            "tlsrec_fake_rich",
            "TLS record + rich fake",
            "tlsrec_fake",
            tlsrec_fake_rich,
            vec!["Randomized fake TLS material with original ClientHello framing"],
        ),
        candidate_spec("split_host", "Split Host", "split", split_host),
        candidate_spec("disorder_host", "Disorder host", "disorder", disorder_host),
        candidate_spec("tlsrec_disorder", "TLS record + disorder", "tlsrec_disorder", tlsrec_disorder),
        candidate_spec("oob_host", "OOB host", "oob", build_oob_host_candidate(base)),
        candidate_spec("tlsrec_oob", "TLS record + OOB", "tlsrec_oob", build_tlsrec_oob_candidate(base)),
        candidate_spec("disoob_host", "Disorder + OOB host", "disoob", build_disoob_host_candidate(base)),
        candidate_spec(
            "tlsrec_disoob",
            "TLS record + disorder OOB",
            "tlsrec_disoob",
            build_tlsrec_disoob_candidate(base),
        ),
        candidate_spec(
            "tlsrandrec_split",
            "TLS random record + split",
            "tlsrandrec_split",
            build_tlsrandrec_split_candidate(base),
        ),
        candidate_spec(
            "tlsrandrec_disorder",
            "TLS random record + disorder",
            "tlsrandrec_disorder",
            build_tlsrandrec_disorder_candidate(base),
        ),
        candidate_spec_with_notes(
            "tlsrec_seqovl_midsld",
            "TLS record + seq overlap (midsld)",
            "tlsrec_seqovl",
            build_tlsrec_seqovl_candidate(base, "midsld"),
            vec!["Sequence overlap at midsld; falls back to split if TCP_REPAIR unavailable"],
        ),
        candidate_spec_with_notes(
            "tlsrec_seqovl_sniext",
            "TLS record + seq overlap (sniext)",
            "tlsrec_seqovl",
            build_tlsrec_seqovl_candidate(base, "sniext"),
            vec!["Sequence overlap at sniext; falls back to split if TCP_REPAIR unavailable"],
        ),
        candidate_spec("tlsrec_fakeddisorder", "TLS record + fakeddisorder", "fake_approx", tlsrec_fakeddisorder),
        candidate_spec("tlsrec_fakedsplit", "TLS record + fakedsplit", "fake_approx", tlsrec_fakedsplit),
        candidate_spec("tlsrec_hostfake", "TLS record + hostfake", "hostfake", tlsrec_hostfake),
        candidate_spec("parser_only", "Parser-only", "parser", parser_only),
        candidate_spec("parser_unixeol", "Parser + Unix EOL", "parser_aggressive", parser_unixeol),
        candidate_spec("parser_methodeol", "Parser + Method EOL", "parser_aggressive", parser_methodeol),
        candidate_spec_with_notes_and_eligibility(
            "ech_split",
            "ECH extension split",
            "ech_split",
            CandidateEligibility::RequiresEchCapability,
            ech_split,
            vec!["Runs only when the baseline proves an ECH-capable HTTPS path"],
        ),
        candidate_spec_with_notes_and_eligibility(
            "ech_tlsrec",
            "ECH TLS record split",
            "ech_tlsrec",
            CandidateEligibility::RequiresEchCapability,
            ech_tlsrec,
            vec!["Runs only when the baseline proves an ECH-capable HTTPS path"],
        ),
    ];
    if ipfrag_capable {
        candidates.push(candidate_spec_with_notes(
            "ipfrag2",
            "IP fragmentation",
            "ipfrag2",
            build_ipfrag_candidate(base),
            vec!["VPN-only raw-socket TCP fragmentation of the first application-data segment"],
        ));
    }
    candidates
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
    let mut candidates = vec![
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
            vec!["Uses fixed compatibility QUIC fake packets"],
        ),
        candidate_spec_with_notes(
            "quic_realistic_burst",
            "QUIC realistic burst",
            "quic_burst",
            build_quic_candidate(base_tcp, true, "realistic_initial"),
            vec!["Uses realistic QUIC Initial packets with the target SNI"],
        ),
    ];
    candidates.push(candidate_spec_with_notes(
        "quic_sni_split",
        "QUIC SNI split",
        "quic_sni_split",
        build_quic_sni_split_candidate(base_tcp),
        vec!["Splits QUIC Initial at SNI boundary"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_fake_version",
        "QUIC fake version",
        "quic_fake_version",
        build_quic_fake_version_candidate(base_tcp),
        vec!["Sends QUIC packet with unrecognized version field"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_dummy_prepend",
        "QUIC dummy prepend",
        "quic_dummy_prepend",
        build_quic_dummy_prepend_candidate(base_tcp),
        vec!["Prepends random short-header packets before real Initial"],
    ));
    if supports_udp_ip_fragmentation() {
        candidates.push(candidate_spec_with_notes(
            "quic_ipfrag2",
            "QUIC IP fragmentation",
            "quic_ipfrag2",
            build_quic_ipfrag_candidate(base_tcp),
            vec!["VPN-only raw-socket fragmentation of the first QUIC Initial datagram"],
        ));
    }
    candidates
}

pub(crate) fn candidate_spec(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes_and_eligibility(id, label, family, CandidateEligibility::Always, config, Vec::new())
}

pub(crate) fn candidate_spec_with_notes(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes_and_eligibility(id, label, family, CandidateEligibility::Always, config, notes)
}

pub(crate) fn candidate_spec_with_notes_and_eligibility(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    eligibility: CandidateEligibility,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    let requires_fake_ttl = config_requires_fake_ttl(&config);
    StrategyCandidateSpec {
        id,
        label,
        family,
        eligibility,
        config,
        notes,
        preserve_adaptive_fake_ttl: false,
        warmup: CandidateWarmup::None,
        requires_fake_ttl,
    }
}

/// Returns `true` when the config contains at least one TCP step that relies on
/// TTL manipulation to suppress the fake packet at the DPI device. Steps in
/// this set send a segment with a short TTL that expires before it reaches the
/// target server, so it is essential that `setsockopt(IP_TTL)` succeeds.
fn config_requires_fake_ttl(config: &ProxyUiConfig) -> bool {
    config.chains.tcp_steps.iter().any(|step| {
        matches!(step.kind.as_str(), "fake" | "fakedsplit" | "fakeddisorder" | "hostfake" | "disorder" | "disoob")
    })
}

/// Probes whether the current process is allowed to set a custom IP TTL on TCP
/// sockets. On Android VPN/tun mode `setsockopt(IP_TTL)` fails with EPERM, so
/// this function returns `false` in that environment.
pub(crate) fn probe_fake_ttl_capability() -> bool {
    use std::net::TcpListener;
    let listener = match TcpListener::bind("127.0.0.1:0") {
        Ok(l) => l,
        Err(_) => return false,
    };
    let result = listener.set_ttl(1);
    // Restore a sane TTL regardless of outcome — we borrowed the socket briefly.
    let _ = listener.set_ttl(64);
    result.is_ok()
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

fn build_disorder_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("disorder", "host+2")];
    config
}

fn build_tlsrec_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("disorder", "host+2")];
    config
}

fn build_oob_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("oob", "host+2")];
    config
}

fn build_tlsrec_oob_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("oob", "host+2")];
    config
}

fn build_disoob_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("disoob", "host+2")];
    config
}

fn build_tlsrec_disoob_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("disoob", "host+2")];
    config
}

fn build_tlsrandrec_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrandrec", "sniext+4"), tcp_step("split", "host+2")];
    config
}

fn build_tlsrandrec_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrandrec", "sniext+4"), tcp_step("disorder", "host+2")];
    config
}

pub(crate) fn build_ech_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("split", "echext")];
    config
}

pub(crate) fn build_ech_tlsrec_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "echext")];
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
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: None,
            inter_segment_delay_ms: 0,
        },
    ];
    if with_split {
        steps.push(tcp_step("split", "midsld"));
    }
    config.chains.tcp_steps = steps;
    config
}

pub(crate) fn build_tlsrec_seqovl_candidate(base: &ProxyUiConfig, marker: &str) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "seqovl".to_string(),
            marker: marker.to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            overlap_size: 12,
            fake_mode: "profile".to_string(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: Some(ProxyUiActivationFilter {
                round: Some(ProxyUiNumericRange { start: Some(1), end: Some(1) }),
                payload_size: None,
                stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(1500) }),
            }),
            inter_segment_delay_ms: 0,
        },
    ];
    config
}

pub(crate) fn build_quic_candidate(base_tcp: &ProxyUiConfig, enabled: bool, profile: &str) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = enabled;
    config.chains.udp_steps = if enabled {
        vec![ProxyUiUdpChainStep { kind: "fake_burst".to_string(), count: 4, split_bytes: 0, activation_filter: None }]
    } else {
        Vec::new()
    };
    config.quic.fake_profile = profile.to_string();
    config.quic.fake_host.clear();
    config
}

pub(crate) fn build_ipfrag_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("ipfrag2", "host+2")];
    config
}

pub(crate) fn build_quic_ipfrag_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 0,
        split_bytes: 8,
        activation_filter: None,
    }];
    config.quic.fake_profile = "disabled".to_string();
    config.quic.fake_host.clear();
    config
}

fn build_quic_sni_split_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_sni_split".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

fn build_quic_fake_version_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_fake_version".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

fn build_quic_dummy_prepend_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "dummy_prepend".to_string(),
        count: 3,
        split_bytes: 0,
        activation_filter: None,
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

fn probe_ip_fragmentation_capabilities() -> ripdpi_runtime::platform::IpFragmentationCapabilities {
    ripdpi_runtime::platform::probe_ip_fragmentation_capabilities(None).unwrap_or_default()
}

fn supports_tcp_ip_fragmentation_for(capabilities: ripdpi_runtime::platform::IpFragmentationCapabilities) -> bool {
    capabilities.supports_tcp_ip_fragmentation(true)
}

fn supports_udp_ip_fragmentation_for(capabilities: ripdpi_runtime::platform::IpFragmentationCapabilities) -> bool {
    capabilities.supports_udp_ip_fragmentation(true)
}

fn supports_tcp_ip_fragmentation() -> bool {
    supports_tcp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

fn supports_udp_ip_fragmentation() -> bool {
    supports_udp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

fn supports_seqovl() -> bool {
    ripdpi_runtime::platform::seqovl_supported()
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
    let requires_fake_ttl = config_requires_fake_ttl(&config);
    StrategyCandidateSpec {
        id: "adaptive_fake_ttl",
        label: "Adaptive fake TTL",
        family: "adaptive_fake_ttl",
        eligibility: CandidateEligibility::Always,
        config,
        notes: vec![
            "Runs an unscored warm-up pass before measured probes",
            "Keeps adaptive fake TTL enabled during candidate execution",
        ],
        preserve_adaptive_fake_ttl: true,
        warmup: CandidateWarmup::AdaptiveFakeTtl,
        requires_fake_ttl,
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
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
        inter_segment_delay_ms: 0,
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
    fn seqovl_candidates_always_included() {
        let base = minimal_ui_config();
        let quick = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1");
        let full = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1");

        // SeqOverlap candidates are now unconditionally included; the runtime
        // falls back to split when TCP_REPAIR/CAP_NET_ADMIN is unavailable.
        assert!(quick.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_midsld"));
        assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_midsld"));
        assert!(full.tcp_candidates.iter().any(|candidate| candidate.id == "tlsrec_seqovl_sniext"));
    }

    #[test]
    fn build_tlsrec_seqovl_candidate_sets_hard_gate_and_fields() {
        let config = build_tlsrec_seqovl_candidate(&minimal_ui_config(), "midsld");
        let steps = &config.chains.tcp_steps;

        assert_eq!(steps.len(), 2);
        assert_eq!(steps[0].kind, "tlsrec");
        assert_eq!(steps[0].marker, "extlen");
        assert_eq!(steps[1].kind, "seqovl");
        assert_eq!(steps[1].marker, "midsld");
        assert_eq!(steps[1].overlap_size, 12);
        assert_eq!(steps[1].fake_mode, "profile");
        let filter = steps[1].activation_filter.as_ref().expect("seqovl activation filter");
        assert_eq!(filter.round.as_ref().and_then(|value| value.start), Some(1));
        assert_eq!(filter.round.as_ref().and_then(|value| value.end), Some(1));
        assert_eq!(filter.stream_bytes.as_ref().and_then(|value| value.start), Some(0));
        assert_eq!(filter.stream_bytes.as_ref().and_then(|value| value.end), Some(1500));
    }

    #[test]
    fn build_tcp_candidates_marks_ech_candidates_as_ech_only_and_targets_echext() {
        let candidates = build_tcp_candidates(&minimal_ui_config());
        let ech_split = candidates.iter().find(|candidate| candidate.id == "ech_split").expect("ech_split candidate");
        let ech_tlsrec =
            candidates.iter().find(|candidate| candidate.id == "ech_tlsrec").expect("ech_tlsrec candidate");

        assert_eq!(ech_split.eligibility, CandidateEligibility::RequiresEchCapability);
        assert_eq!(ech_split.config.chains.tcp_steps.len(), 1);
        assert_eq!(ech_split.config.chains.tcp_steps[0].kind, "split");
        assert_eq!(ech_split.config.chains.tcp_steps[0].marker, "echext");
        assert!(ech_split.notes.iter().any(|note| note.contains("ECH-capable HTTPS path")));

        assert_eq!(ech_tlsrec.eligibility, CandidateEligibility::RequiresEchCapability);
        assert_eq!(ech_tlsrec.config.chains.tcp_steps.len(), 1);
        assert_eq!(ech_tlsrec.config.chains.tcp_steps[0].kind, "tlsrec");
        assert_eq!(ech_tlsrec.config.chains.tcp_steps[0].marker, "echext");
        assert!(ech_tlsrec.notes.iter().any(|note| note.contains("ECH-capable HTTPS path")));
    }

    #[test]
    fn ipfrag_candidates_follow_platform_capability_probe() {
        let base = minimal_ui_config();
        let tcp_candidates = build_tcp_candidates(&base);
        let quic_candidates = build_quic_candidates(&base);
        let tcp_ipfrag_capable = supports_tcp_ip_fragmentation();
        let udp_ipfrag_capable = supports_udp_ip_fragmentation();

        assert_eq!(tcp_candidates.iter().any(|candidate| candidate.id == "ipfrag2"), tcp_ipfrag_capable);
        assert_eq!(quic_candidates.iter().any(|candidate| candidate.id == "quic_ipfrag2"), udp_ipfrag_capable);
    }

    #[test]
    fn ipfrag_capability_helpers_split_tcp_and_udp_requirements() {
        let udp_only =
            ripdpi_runtime::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: false };
        assert!(!supports_tcp_ip_fragmentation_for(udp_only));
        assert!(supports_udp_ip_fragmentation_for(udp_only));

        let tcp_and_udp =
            ripdpi_runtime::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true };
        assert!(supports_tcp_ip_fragmentation_for(tcp_and_udp));
        assert!(supports_udp_ip_fragmentation_for(tcp_and_udp));
    }

    #[test]
    fn default_runtime_encrypted_dns_context_returns_adguard_doh() {
        let ctx = default_runtime_encrypted_dns_context();
        assert_eq!(ctx.protocol, "doh");
        assert_eq!(ctx.host, "dns.adguard-dns.com");
        assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.adguard-dns.com"));
        assert!(!ctx.bootstrap_ips.is_empty());
        assert!(ctx.bootstrap_ips.iter().any(|ip| ip == "94.140.14.14"));
    }

    #[test]
    fn strategy_probe_encrypted_dns_label_uses_doh_url_when_present() {
        let ctx = default_runtime_encrypted_dns_context();
        let label = strategy_probe_encrypted_dns_label(&ctx);
        assert!(label.contains("dns.adguard-dns.com"));
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

    #[test]
    fn quick_v1_suite_has_threshold_2() {
        let base = minimal_ui_config();
        let suite = build_strategy_probe_suite("quick_v1", &base).expect("quick_v1 suite");
        assert_eq!(suite.family_failure_threshold, 2);
    }

    #[test]
    fn full_matrix_v1_suite_has_threshold_4() {
        let base = minimal_ui_config();
        let suite = build_strategy_probe_suite("full_matrix_v1", &base).expect("full_matrix_v1 suite");
        assert_eq!(suite.family_failure_threshold, 4);
    }
}
