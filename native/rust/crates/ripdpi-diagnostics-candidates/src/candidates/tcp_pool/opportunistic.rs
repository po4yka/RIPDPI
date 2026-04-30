use crate::candidates::prelude::*;

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
