use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{
    ProxyConfigPayload, ProxyEncryptedDnsContext, ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig,
    ProxyUiNumericRange, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate, ProxyUiTcpRotationConfig,
    ProxyUiUdpChainStep, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
};
use ripdpi_runtime::platform::RuntimeCapability;
use socket2::{Domain, Protocol, Socket, Type};

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
    pub(crate) requires_tcp_fast_open: bool,
    /// Runtime capabilities that must be available for this candidate to emit
    /// packets as designed. An empty slice means the candidate works on every
    /// platform without special privileges.
    ///
    /// Use [`enumerate_capable_candidates`] to filter a pool against a live
    /// capability lookup before promoting a winner.
    pub(crate) requires_capabilities: &'static [RuntimeCapability],
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
        session_overrides: None,
    })
    .expect("serialize ui proxy config")
}

/// Returns the hardcoded AdGuard DoH configuration used as fallback when no
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
/// Precedence: user-supplied runtime context > AdGuard DoH default.
/// When the runtime context is absent or has no `encrypted_dns` field, the
/// AdGuard fallback from [`default_runtime_encrypted_dns_context`] is used
/// and a debug log is emitted so operators can correlate any DNS-path mismatch.
pub(crate) fn strategy_probe_encrypted_dns_context(
    runtime_context: Option<&ProxyRuntimeContext>,
) -> ProxyEncryptedDnsContext {
    match runtime_context.and_then(|value| value.encrypted_dns.clone()) {
        Some(ctx) => ctx,
        None => {
            let fallback = default_runtime_encrypted_dns_context();
            tracing::debug!(
                "no runtime encrypted DNS context provided; falling back to default {} for strategy probes",
                strategy_probe_encrypted_dns_label(&fallback)
            );
            fallback
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

/// Builds the primary TCP candidate set: candidates that work on every platform
/// without special runtime capabilities (no TTL write, no raw socket, no root).
///
/// This is the default pool for non-root Android contexts. It covers HTTP
/// parser evasions, TLS record splitting, split-host, delayed-split, OOB,
/// seq-overlap, hostfake-with-split, hostfake-random, and ECH variants.
/// Every candidate here has an empty `requires_capabilities` slice.
pub(crate) fn build_primary_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let baseline = sanitize_current_probe_config(base);
    let parser_only = build_parser_only_candidate(base);
    let parser_unixeol = build_parser_unixeol_candidate(base);
    let parser_methodeol = build_parser_methodeol_candidate(base);
    let parser_methodspace = build_parser_methodspace_candidate(base);
    let parser_hostpad = build_parser_hostpad_candidate(base);
    let parser_host_extra_space = build_parser_host_extra_space_candidate(base);
    let parser_host_tab = build_parser_host_tab_candidate(base);
    let split_host = build_split_host_candidate(base);
    let ech_split = build_ech_split_candidate(base);
    let ech_tlsrec = build_ech_tlsrec_candidate(base);
    let tlsrec_split_host = build_tlsrec_split_host_candidate(base);
    let tlsrec_hostfake_split = build_tlsrec_hostfake_candidate(base, true);
    let ipfrag_capable = supports_tcp_ip_fragmentation();

    let mut candidates = vec![
        candidate_spec("baseline_current", "Current strategy", "baseline", baseline),
        candidate_spec("tlsrec_split_host", "TLS record + split host", "tlsrec_split", tlsrec_split_host.clone()),
        candidate_spec_with_notes(
            "tlsrec_hostfake_split",
            "TLS record + hostfake split",
            "hostfake",
            tlsrec_hostfake_split,
            vec!["Adds a follow-up split after hostfake midhost reconstruction"],
        ),
        candidate_spec_with_notes(
            "tlsrec_hostfake_random",
            "TLS record + hostfake (random)",
            "hostfake",
            build_tlsrec_hostfake_random_candidate(base),
            vec!["Random domain per connection defeats DPI fake-SNI caching"],
        ),
        candidate_spec("split_host", "Split Host", "split", split_host.clone()),
        candidate_spec("oob_host", "OOB host", "oob", build_oob_host_candidate(base)),
        candidate_spec("tlsrec_oob", "TLS record + OOB", "tlsrec_oob", build_tlsrec_oob_candidate(base)),
        candidate_spec(
            "tlsrandrec_split",
            "TLS random record + split",
            "tlsrandrec_split",
            build_tlsrandrec_split_candidate(base),
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
        candidate_spec_with_notes(
            "split_delayed_50ms",
            "Split host + 50ms delay",
            "split_delayed",
            build_split_delayed_candidate(base, 50),
            vec!["50ms inter-segment delay exploits DPI timeout windows"],
        ),
        candidate_spec_with_notes(
            "split_delayed_150ms",
            "Split host + 150ms delay",
            "split_delayed",
            build_split_delayed_candidate(base, 150),
            vec!["150ms inter-segment delay for longer DPI timeout windows"],
        ),
        candidate_spec("parser_only", "Parser-only", "parser", parser_only),
        candidate_spec("parser_hostpad", "Parser + Host Pad", "parser", parser_hostpad),
        candidate_spec("parser_unixeol", "Parser + Unix EOL", "parser_aggressive", parser_unixeol),
        candidate_spec("parser_methodeol", "Parser + Method EOL", "parser_aggressive", parser_methodeol),
        candidate_spec("parser_methodspace", "Parser + Method Space", "parser_aggressive", parser_methodspace),
        candidate_spec("parser_host_tab", "Parser + Host Tab", "parser", parser_host_tab),
        candidate_spec(
            "parser_host_extra_space",
            "Parser + Host Extra Space",
            "parser_aggressive",
            parser_host_extra_space,
        ),
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
    if probe_tcp_fast_open_capability() && allows_direct_tfo_candidates(base) {
        candidates.push(candidate_spec_with_notes(
            "tlsrec_split_host_tfo",
            "TLS record + split host + TFO",
            "tlsrec_split_tfo",
            build_tfo_variant(&tlsrec_split_host),
            vec!["Enables TCP Fast Open for the upstream connect path"],
        ));
        candidates.push(candidate_spec_with_notes(
            "split_host_tfo",
            "Split host + TFO",
            "split_tfo",
            build_tfo_variant(&split_host),
            vec!["Enables TCP Fast Open for the upstream connect path"],
        ));
    }
    if ipfrag_capable {
        candidates.push(candidate_spec_with_notes(
            "ipfrag2",
            "IP fragmentation",
            "ipfrag2",
            build_ipfrag_candidate(base),
            vec!["VPN-only raw-socket TCP fragmentation of the first application-data segment"],
        ));
        for (id, label, profile, note) in [
            (
                "ipfrag2_hopbyhop",
                "IP fragmentation + Hop-by-Hop",
                "hopByHop",
                "Adds one Hop-by-Hop header before fragmentation",
            ),
            (
                "ipfrag2_hopbyhop2",
                "IP fragmentation + Hop-by-Hop2",
                "hopByHop2",
                "Adds the double-header Tier 2 IPv6 extension profile",
            ),
            (
                "ipfrag2_destopt",
                "IP fragmentation + Dest Opt",
                "destOpt",
                "Adds one Destination Options header before fragmentation",
            ),
            (
                "ipfrag2_hopbyhop_destopt",
                "IP fragmentation + HBH + Dest Opt",
                "hopByHopDestOpt",
                "Adds both Hop-by-Hop and Destination Options headers before fragmentation",
            ),
        ] {
            candidates.push(candidate_spec_with_notes(
                id,
                label,
                "ipfrag2_ipv6_ext",
                build_ipfrag_candidate_with_ipv6_ext(base, profile),
                vec![note, "VPN-only raw-socket TCP fragmentation variant for IPv6-capable paths"],
            ));
        }
    }
    candidates
}

/// Builds the opportunistic TCP candidate set: candidates that require
/// [`RuntimeCapability::TtlWrite`] (IP TTL socket option) to emit packets as
/// designed, plus the fixed-duplicate HostFake variant that depends on the
/// same TTL path.
///
/// These are excluded from the default non-root pool but are included in
/// the full probe suite so that probers can discover their effectiveness
/// where the capability is available. Use [`enumerate_capable_candidates`]
/// with a live capability lookup before promoting a winner from this set.
pub(crate) fn build_opportunistic_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let disorder_host = build_disorder_host_candidate(base);
    let tlsrec_disorder = build_tlsrec_disorder_candidate(base);
    let tlsrec_fake_rich = build_tlsrec_fake_rich_candidate(base);
    let tlsrec_fake_seqgroup = build_tlsrec_fake_seqgroup_candidate(base);
    let fake_synfin = build_tlsrec_fake_flag_candidate(base, "syn|fin");
    let fake_pshurg = build_tlsrec_fake_flag_candidate(base, "psh|urg");
    let tlsrec_fakedsplit = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    let tlsrec_fakeddisorder = build_tlsrec_fake_approx_candidate(base, "fakeddisorder");
    // Fixed-duplicate HostFake (no follow-up split, no random host): demoted
    // from primary because it relies on TTL expiry of the fake segment and
    // produces no differentiation over tlsrec_hostfake_split on capable hosts.
    let tlsrec_hostfake = build_tlsrec_hostfake_candidate(base, false);

    vec![
        candidate_spec("disorder_host", "Disorder host", "disorder", disorder_host),
        candidate_spec("tlsrec_disorder", "TLS record + disorder", "tlsrec_disorder", tlsrec_disorder),
        candidate_spec("disoob_host", "Disorder + OOB host", "disoob", build_disoob_host_candidate(base)),
        candidate_spec(
            "tlsrec_disoob",
            "TLS record + disorder OOB",
            "tlsrec_disoob",
            build_tlsrec_disoob_candidate(base),
        ),
        candidate_spec(
            "tlsrandrec_disorder",
            "TLS random record + disorder",
            "tlsrandrec_disorder",
            build_tlsrandrec_disorder_candidate(base),
        ),
        candidate_spec_with_notes(
            "tlsrec_fake_rich",
            "TLS record + rich fake",
            "tlsrec_fake",
            tlsrec_fake_rich,
            vec!["Randomized fake TLS material with original ClientHello framing"],
        ),
        candidate_spec_with_notes(
            "tlsrec_fake_seqgroup",
            "TLS record + rich fake (seqgroup)",
            "tlsrec_fake",
            tlsrec_fake_seqgroup,
            vec!["Uses seqgroup IPv4 IDs so fake and original raw packets stay in one exact sequence"],
        ),
        candidate_spec_with_notes(
            "fake_synfin",
            "Fake packet + SYN|FIN",
            "fake_flags",
            fake_synfin,
            vec!["Applies SYN and FIN on the fake packet while preserving the normal payload flow"],
        ),
        candidate_spec_with_notes(
            "fake_pshurg",
            "Fake packet + PSH|URG",
            "fake_flags",
            fake_pshurg,
            vec!["Applies PSH and URG on the fake packet while preserving the normal payload flow"],
        ),
        candidate_spec("tlsrec_fakeddisorder", "TLS record + fakeddisorder", "fake_approx", tlsrec_fakeddisorder),
        candidate_spec("tlsrec_fakedsplit", "TLS record + fakedsplit", "fake_approx", tlsrec_fakedsplit),
        candidate_spec_with_notes(
            "tlsrec_hostfake",
            "TLS record + hostfake",
            "hostfake",
            tlsrec_hostfake,
            vec!["Fixed-duplicate HostFake; demoted to opportunistic pool (requires TtlWrite)"],
        ),
    ]
}

/// Builds the rooted TCP candidate set: candidates that require
/// [`RuntimeCapability::RawTcpFakeSend`] or
/// [`RuntimeCapability::RootHelperAvailable`] (TCP_REPAIR / SOCK_RAW via the
/// root helper). These are only added to the probe suite when the platform
/// probe confirms root-level access is available (`root_mode_enabled`).
///
/// Nothing is deleted: callers that need the full superset (e.g. the probe
/// suite) combine primary + opportunistic + rooted. Non-root contexts use
/// `build_primary_candidates()` alone (optionally extended by
/// `build_opportunistic_candidates()` after a capability check).
pub(crate) fn build_rooted_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let tcp_repair_capable = probe_ip_fragmentation_capabilities().tcp_repair;
    if !tcp_repair_capable {
        return Vec::new();
    }
    vec![
        candidate_spec_with_notes(
            "fake_rst",
            "Fake RST (TTL trick)",
            "fake_rst",
            build_fake_rst_candidate(base),
            vec!["Sends a fake RST with low TTL to clear DPI state; requires root"],
        ),
        candidate_spec_with_notes(
            "multi_disorder",
            "Multi-disorder (3+ segments)",
            "multi_disorder",
            build_multi_disorder_candidate(base),
            vec!["3+ out-of-order TCP segments via TCP_REPAIR; requires root"],
        ),
    ]
}

/// Builds the full TCP candidate set for strategy probing: primary +
/// opportunistic + rooted. The probe runner needs all candidates so it can
/// measure effectiveness across platforms; capability filtering (via
/// [`enumerate_capable_candidates`]) is the caller's responsibility when
/// *promoting* a winner for a non-root context.
pub(crate) fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_primary_candidates(base);
    candidates.extend(build_opportunistic_candidates(base));
    candidates.extend(build_rooted_candidates(base));
    candidates
}

fn allows_direct_tfo_candidates(base: &ProxyUiConfig) -> bool {
    !base.upstream_relay.enabled || base.upstream_relay.kind.eq_ignore_ascii_case("off")
}

pub(crate) fn build_full_matrix_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_tcp_candidates(base);
    candidates.push(build_circular_tlsrec_split_spec(base));
    candidates.push(candidate_spec_with_notes(
        "tlsrec_fakedsplit_altorder1",
        "TLS record + fakedsplit (altorder 1)",
        "fake_approx",
        build_tlsrec_fakedsplit_altorder_candidate(base, "1"),
        vec!["Emits both fake regions before both genuine fakedsplit regions"],
    ));
    candidates.push(candidate_spec_with_notes(
        "tlsrec_fakedsplit_altorder2",
        "TLS record + fakedsplit (altorder 2)",
        "fake_approx",
        build_tlsrec_fakedsplit_altorder_candidate(base, "2"),
        vec!["Interleaves genuine then fake for each fakedsplit region pair"],
    ));
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
        candidate_spec_with_notes(
            "quic_multi_initial_realistic",
            "QUIC multi-initial realistic",
            "quic_multi_initial_realistic",
            build_quic_multi_initial_realistic_candidate(base_tcp),
            vec!["Sends multiple realistic QUIC Initials to pressure parser state tracking"],
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
        "quic_crypto_split",
        "QUIC CRYPTO split",
        "quic_crypto_split",
        build_quic_crypto_split_candidate(base_tcp),
        vec!["Splits the QUIC CRYPTO payload into two application-visible pieces"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_padding_ladder",
        "QUIC padding ladder",
        "quic_padding_ladder",
        build_quic_padding_ladder_candidate(base_tcp),
        vec!["Adds multiple padded dummy packets before the real QUIC Initial"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_cid_churn",
        "QUIC CID churn",
        "quic_cid_churn",
        build_quic_cid_churn_candidate(base_tcp),
        vec!["Mutates QUIC connection ID state before the first real Initial"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_packet_number_gap",
        "QUIC packet number gap",
        "quic_packet_number_gap",
        build_quic_packet_number_gap_candidate(base_tcp),
        vec!["Injects short packets to force a packet-number discontinuity"],
    ));
    candidates.push(candidate_spec_with_notes(
        "quic_version_negotiation_decoy",
        "QUIC version negotiation decoy",
        "quic_version_negotiation_decoy",
        build_quic_version_negotiation_decoy_candidate(base_tcp),
        vec!["Injects a fake version-negotiation style prelude before the Initial"],
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
        for (id, label, profile, note) in [
            (
                "quic_ipfrag2_hopbyhop",
                "QUIC IP fragmentation + Hop-by-Hop",
                "hopByHop",
                "Adds one Hop-by-Hop header before fragmenting the QUIC Initial datagram",
            ),
            (
                "quic_ipfrag2_hopbyhop2",
                "QUIC IP fragmentation + Hop-by-Hop2",
                "hopByHop2",
                "Adds the double-header Tier 2 IPv6 extension profile before fragmentation",
            ),
            (
                "quic_ipfrag2_destopt",
                "QUIC IP fragmentation + Dest Opt",
                "destOpt",
                "Adds one Destination Options header before fragmenting the QUIC Initial datagram",
            ),
            (
                "quic_ipfrag2_hopbyhop_destopt",
                "QUIC IP fragmentation + HBH + Dest Opt",
                "hopByHopDestOpt",
                "Adds both Hop-by-Hop and Destination Options headers before fragmentation",
            ),
        ] {
            candidates.push(candidate_spec_with_notes(
                id,
                label,
                "quic_ipfrag2_ipv6_ext",
                build_quic_ipfrag_candidate_with_ipv6_ext(base_tcp, profile),
                vec![note, "VPN-only raw-socket QUIC fragmentation variant for IPv6-capable paths"],
            ));
        }
    }
    candidates.push(candidate_spec(
        "quic_disabled",
        "QUIC disabled",
        "quic_disabled",
        build_quic_candidate(base_tcp, false, "disabled"),
    ));
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
    let requires_tcp_fast_open = config.listen.tcp_fast_open;
    let requires_capabilities = config_requires_capabilities(&config);
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
        requires_tcp_fast_open,
        requires_capabilities,
    }
}

/// Returns `true` when the config contains at least one TCP step that relies on
/// TTL manipulation to suppress the fake packet at the DPI device. Steps in
/// this set send a segment with a short TTL that expires before it reaches the
/// target server, so it is essential that `setsockopt(IP_TTL)` succeeds.
fn config_requires_fake_ttl(config: &ProxyUiConfig) -> bool {
    let step_requires_fake_ttl = |step: &ProxyUiTcpChainStep| {
        matches!(step.kind.as_str(), "fake" | "fakedsplit" | "fakeddisorder" | "hostfake" | "disorder" | "disoob")
    };
    config.chains.tcp_steps.iter().any(step_requires_fake_ttl)
        || config.chains.tcp_rotation.as_ref().is_some_and(|rotation| {
            rotation.candidates.iter().flat_map(|candidate| candidate.tcp_steps.iter()).any(step_requires_fake_ttl)
        })
}

/// Maps a candidate config to the set of [`RuntimeCapability`] entries that
/// must be available for the candidate to emit packets as designed.
///
/// Step-kind → capability mapping:
/// - `fake`, `fakedsplit`, `fakeddisorder`, `hostfake`, `disorder`, `disoob`
///   → [`RuntimeCapability::TtlWrite`] (TTL manipulation to expire fakes before target).
/// - `fakerst` → [`RuntimeCapability::RawTcpFakeSend`] (raw-socket fake RST path).
/// - `multidisorder` → [`RuntimeCapability::RootHelperAvailable`] (TCP_REPAIR / root).
///
/// Returns a `'static` slice so it can be stored in [`StrategyCandidateSpec`]
/// without allocation.
fn config_requires_capabilities(config: &ProxyUiConfig) -> &'static [RuntimeCapability] {
    static TTL_WRITE: &[RuntimeCapability] = &[RuntimeCapability::TtlWrite];
    static RAW_TCP: &[RuntimeCapability] = &[RuntimeCapability::RawTcpFakeSend];
    static ROOT_HELPER: &[RuntimeCapability] = &[RuntimeCapability::RootHelperAvailable];

    let all_steps = config.chains.tcp_steps.iter().chain(
        config
            .chains
            .tcp_rotation
            .as_ref()
            .into_iter()
            .flat_map(|r| r.candidates.iter())
            .flat_map(|c| c.tcp_steps.iter()),
    );

    let mut needs_ttl = false;
    let mut needs_raw_tcp = false;
    let mut needs_root = false;

    for step in all_steps {
        match step.kind.as_str() {
            "fake" | "fakedsplit" | "fakeddisorder" | "hostfake" | "disorder" | "disoob" => {
                needs_ttl = true;
            }
            "fakerst" => {
                needs_raw_tcp = true;
            }
            "multidisorder" => {
                needs_root = true;
            }
            _ => {}
        }
    }

    // Return the most specific static slice. When multiple capabilities are
    // required we conservatively return the highest-privilege one; in practice
    // no single candidate currently needs more than one capability class.
    if needs_root {
        ROOT_HELPER
    } else if needs_raw_tcp {
        RAW_TCP
    } else if needs_ttl {
        TTL_WRITE
    } else {
        &[]
    }
}

/// Filters `candidates` to those whose required capabilities are all available
/// according to `lookup`.
///
/// `lookup` receives a [`RuntimeCapability`] and returns `true` when that
/// capability is confirmed available. Candidates with an empty
/// `requires_capabilities` slice always pass through.
///
/// This function is intentionally a pure filter: it does not perform its own
/// platform probes and does not consult any global cache. The caller supplies
/// the lookup so that real execution (slice 2.4) and tests can each provide the
/// appropriate source.
pub(crate) fn enumerate_capable_candidates(
    candidates: Vec<StrategyCandidateSpec>,
    lookup: &dyn Fn(RuntimeCapability) -> bool,
) -> Vec<StrategyCandidateSpec> {
    candidates.into_iter().filter(|c| c.requires_capabilities.iter().all(|&cap| lookup(cap))).collect()
}

/// Probes whether the current process is allowed to set a custom IP TTL on TCP
/// sockets. On Android VPN/tun mode `setsockopt(IP_TTL)` fails with EPERM, so
/// this function returns `false` in that environment.
pub(crate) fn probe_fake_ttl_capability() -> bool {
    use std::net::TcpListener;
    let Ok(listener) = TcpListener::bind("127.0.0.1:0") else { return false };
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
    config.parser_evasions.http_method_space = false;
    config.parser_evasions.http_method_eol = false;
    config.parser_evasions.http_host_pad = false;
    config.parser_evasions.http_unix_eol = false;
    config.parser_evasions.http_host_extra_space = false;
    config.parser_evasions.http_host_tab = false;
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

pub(crate) fn build_parser_methodspace_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_method_space = true;
    config
}

pub(crate) fn build_parser_hostpad_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_pad = true;
    config
}

pub(crate) fn build_parser_host_extra_space_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_extra_space = true;
    config
}

pub(crate) fn build_parser_host_tab_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_tab = true;
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

pub(crate) fn build_circular_tlsrec_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_split_host_candidate(base);
    config.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig {
        fails: 3,
        retrans: 3,
        seq: 65_536,
        rst: 1,
        time_secs: 60,
        cancel_on_failure: None,
        candidates: vec![
            ProxyUiTcpRotationCandidate { tcp_steps: build_tlsrec_hostfake_candidate(base, true).chains.tcp_steps },
            ProxyUiTcpRotationCandidate { tcp_steps: build_tlsrec_fake_rich_candidate(base).chains.tcp_steps },
            ProxyUiTcpRotationCandidate { tcp_steps: build_split_host_candidate(base).chains.tcp_steps },
        ],
    });
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

pub(crate) fn build_tlsrec_fake_seqgroup_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.ip_id_mode = "seqgroup".to_string();
    config
}

pub(crate) fn build_tlsrec_fake_flag_candidate(base: &ProxyUiConfig, flags: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|step| step.kind == "fake") {
        step.tcp_flags_set = flags.to_string();
    }
    config
}

pub(crate) fn build_tlsrec_fake_approx_candidate(base: &ProxyUiConfig, kind: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step(kind, "host+1")];
    config
}

pub(crate) fn build_tlsrec_fakedsplit_altorder_candidate(base: &ProxyUiConfig, fake_order: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|step| step.kind == "fakedsplit") {
        step.fake_order = fake_order.to_string();
    }
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
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: None,
            inter_segment_delay_ms: 0,
            ipv6_extension_profile: "none".to_string(),
            random_fake_host: false,
        },
    ];
    if with_split {
        steps.push(tcp_step("split", "midsld"));
    }
    config.chains.tcp_steps = steps;
    config
}

pub(crate) fn build_split_delayed_candidate(base: &ProxyUiConfig, delay_ms: u32) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    let mut step = tcp_step("split", "host+2");
    step.inter_segment_delay_ms = delay_ms;
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), step];
    config
}

pub(crate) fn build_tlsrec_hostfake_random_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_hostfake_candidate(base, false);
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|s| s.kind == "hostfake") {
        step.random_fake_host = true;
    }
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
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 12,
            fake_mode: "profile".to_string(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: Some(ProxyUiActivationFilter {
                round: Some(ProxyUiNumericRange { start: Some(1), end: Some(1) }),
                payload_size: None,
                stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(1500) }),
                tcp_has_timestamp: None,
                tcp_has_ech: None,
                tcp_window_below: None,
                tcp_mss_below: None,
            }),
            inter_segment_delay_ms: 0,
            ipv6_extension_profile: "none".to_string(),
            random_fake_host: false,
        },
    ];
    config
}

pub(crate) fn build_quic_candidate(base_tcp: &ProxyUiConfig, enabled: bool, profile: &str) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = enabled;
    config.chains.udp_steps = if enabled {
        vec![ProxyUiUdpChainStep {
            kind: "fake_burst".to_string(),
            count: 4,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }]
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

pub(crate) fn build_ipfrag_candidate_with_ipv6_ext(base: &ProxyUiConfig, profile: &str) -> ProxyUiConfig {
    let mut config = build_ipfrag_candidate(base);
    if let Some(step) = config.chains.tcp_steps.first_mut() {
        step.ipv6_extension_profile = profile.to_string();
    }
    config
}

fn build_fake_rst_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    // FakeRst is a pre-send action, followed by a regular split to deliver the payload.
    config.chains.tcp_steps = vec![tcp_step("fakerst", "host+2"), tcp_step("split", "host+2")];
    config
}

fn build_multi_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    // Multi-disorder splits the ClientHello into 3+ out-of-order segments.
    config.chains.tcp_steps = vec![tcp_step("multidisorder", "host+2"), tcp_step("multidisorder", "midsld")];
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
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "disabled".to_string();
    config.quic.fake_host.clear();
    config
}

pub(crate) fn build_quic_ipfrag_candidate_with_ipv6_ext(base_tcp: &ProxyUiConfig, profile: &str) -> ProxyUiConfig {
    let mut config = build_quic_ipfrag_candidate(base_tcp);
    if let Some(step) = config.chains.udp_steps.first_mut() {
        step.ipv6_extension_profile = profile.to_string();
    }
    config
}

pub(crate) fn build_tfo_variant(config: &ProxyUiConfig) -> ProxyUiConfig {
    let mut candidate = config.clone();
    candidate.listen.tcp_fast_open = true;
    candidate
}

fn build_quic_sni_split_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_sni_split".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
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
        ipv6_extension_profile: "none".to_string(),
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
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

fn build_quic_crypto_split_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_crypto_split", 1, 0, "realistic_initial")
}

fn build_quic_padding_ladder_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_padding_ladder", 4, 0, "compat_default")
}

fn build_quic_cid_churn_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_cid_churn", 2, 0, "realistic_initial")
}

fn build_quic_packet_number_gap_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_packet_number_gap", 2, 0, "realistic_initial")
}

fn build_quic_version_negotiation_decoy_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_version_negotiation_decoy", 1, 0, "compat_default")
}

fn build_quic_multi_initial_realistic_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_multi_initial_realistic", 3, 0, "realistic_initial")
}

fn build_quic_step_candidate(
    base_tcp: &ProxyUiConfig,
    kind: &str,
    count: i32,
    split_bytes: i32,
    fake_profile: &str,
) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: kind.to_string(),
        count,
        split_bytes,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = fake_profile.to_string();
    config.quic.fake_host.clear();
    config
}

pub(crate) fn probe_ip_fragmentation_capabilities() -> ripdpi_runtime::platform::IpFragmentationCapabilities {
    ripdpi_runtime::platform::probe_ip_fragmentation_capabilities(None).unwrap_or_default()
}

pub(crate) fn supports_tcp_ip_fragmentation_for(
    capabilities: ripdpi_runtime::platform::IpFragmentationCapabilities,
) -> bool {
    capabilities.supports_tcp_ip_fragmentation(true)
}

pub(crate) fn supports_udp_ip_fragmentation_for(
    capabilities: ripdpi_runtime::platform::IpFragmentationCapabilities,
) -> bool {
    capabilities.supports_udp_ip_fragmentation(true)
}

pub(crate) fn supports_tcp_ip_fragmentation() -> bool {
    supports_tcp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

pub(crate) fn supports_udp_ip_fragmentation() -> bool {
    supports_udp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

pub(crate) fn probe_tcp_fast_open_capability() -> bool {
    let Ok(socket) = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)) else { return false };
    ripdpi_runtime::platform::enable_tcp_fastopen_connect(&socket).is_ok()
}

pub(crate) fn build_circular_tlsrec_split_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    candidate_spec_with_notes(
        "circular_tlsrec_split",
        "Circular TLS record split",
        "circular_tlsrec_split",
        build_circular_tlsrec_split_candidate(base),
        vec![
            "Rotates TLS record strategies between outbound rounds on the same TCP socket",
            "Fallback order: hostfake split -> rich fake -> split host",
        ],
    )
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
    let requires_capabilities = config_requires_capabilities(&config);
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
        requires_tcp_fast_open: false,
        requires_capabilities,
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
        tcp_has_timestamp: None,
        tcp_has_ech: None,
        tcp_window_below: None,
        tcp_mss_below: None,
    }
}

pub(crate) fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
        fake_order: String::new(),
        fake_seq_mode: String::new(),
        tcp_flags_set: String::new(),
        tcp_flags_unset: String::new(),
        tcp_flags_orig_set: String::new(),
        tcp_flags_orig_unset: String::new(),
        overlap_size: 0,
        fake_mode: String::new(),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: None,
        inter_segment_delay_ms: 0,
        ipv6_extension_profile: "none".to_string(),
        random_fake_host: false,
    }
}

#[cfg(test)]
#[path = "candidates_tests.rs"]
mod tests;
