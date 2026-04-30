use super::prelude::*;

pub fn build_primary_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
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
pub fn build_opportunistic_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let disorder_host = build_disorder_host_candidate(base);
    let tlsrec_disorder = build_tlsrec_disorder_candidate(base);
    let tlsrec_fake_rich = build_tlsrec_fake_rich_candidate(base);
    let tlsrec_fake_hrr = build_tlsrec_fake_hrr_candidate(base);
    let tlsrec_fake_seqgroup = build_tlsrec_fake_seqgroup_candidate(base);
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
            vec!["Uses a coherent Chrome-family fake ClientHello instead of randomized fake bytes"],
        ),
        candidate_spec_with_notes(
            "tlsrec_fake_hrr",
            "TLS record + HRR-oriented fake",
            "tlsrec_fake",
            tlsrec_fake_hrr,
            vec![
                "Uses a Chrome-family fake ClientHello that retains supported_groups but strips the x25519 key_share",
                "Targets compliant servers that answer with HelloRetryRequest instead of a generic TLS alert",
            ],
        ),
        candidate_spec_with_notes(
            "tlsrec_fake_seqgroup",
            "TLS record + rich fake (seqgroup)",
            "tlsrec_fake",
            tlsrec_fake_seqgroup,
            vec![
                "Uses a coherent Chrome-family fake ClientHello",
                "Uses seqgroup IPv4 IDs so fake and original raw packets stay in one exact sequence",
            ],
        ),
        candidate_spec_with_notes(
            "tlsrec_fakeddisorder",
            "TLS record + fakeddisorder",
            "fake_approx",
            tlsrec_fakeddisorder,
            vec!["Uses the Chrome-family fake ClientHello profile before approximate fallback emission"],
        ),
        candidate_spec_with_notes(
            "tlsrec_fakedsplit",
            "TLS record + fakedsplit",
            "fake_approx",
            tlsrec_fakedsplit,
            vec!["Uses the Chrome-family fake ClientHello profile before approximate fallback emission"],
        ),
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
pub fn build_rooted_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    vec![candidate_spec_with_notes(
        "multi_disorder",
        "Multi-disorder (3+ segments)",
        "multi_disorder",
        build_multi_disorder_candidate(base),
        vec!["3+ out-of-order TCP segments via TCP_REPAIR; requires root"],
    )]
}

/// Builds the full TCP candidate set for strategy probing: primary +
/// opportunistic + rooted. The probe runner needs all candidates so it can
/// measure effectiveness across platforms; capability filtering (via
/// [`enumerate_capable_candidates`]) is the caller's responsibility when
/// *promoting* a winner for a non-root context.
pub fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_primary_candidates(base);
    candidates.extend(build_opportunistic_candidates(base));
    candidates.extend(build_rooted_candidates(base));
    candidates
}

fn allows_direct_tfo_candidates(base: &ProxyUiConfig) -> bool {
    !base.upstream_relay.enabled || base.upstream_relay.kind.eq_ignore_ascii_case("off")
}

pub fn build_full_matrix_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_tcp_candidates(base);
    candidates.push(candidate_spec_with_notes(
        "fake_rst",
        "Fake RST (TTL trick)",
        "fake_rst",
        build_fake_rst_candidate(base),
        vec!["Sends a fake RST with low TTL to clear DPI state; lab-only rooted experiment"],
    ));
    candidates.push(candidate_spec_with_notes(
        "fake_synfin",
        "Fake packet + SYN|FIN",
        "fake_flags",
        build_tlsrec_fake_flag_candidate(base, "syn|fin"),
        vec!["Applies SYN and FIN on the fake packet while preserving the normal payload flow"],
    ));
    candidates.push(candidate_spec_with_notes(
        "fake_pshurg",
        "Fake packet + PSH|URG",
        "fake_flags",
        build_tlsrec_fake_flag_candidate(base, "psh|urg"),
        vec!["Applies PSH and URG on the fake packet while preserving the normal payload flow"],
    ));
    if supports_tcp_ip_fragmentation() {
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
