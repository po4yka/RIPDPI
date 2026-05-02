use std::collections::BTreeMap;

use ripdpi_failure_classifier::FailureClass;

use crate::candidates::StrategyCandidateSpec;
use crate::util::stable_probe_hash;

pub fn reorder_tcp_candidates_for_failure(
    candidates: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let preferred_ids: &[&str] = match failure_class {
        Some(FailureClass::HttpBlockpage) => &["baseline_current", "tlsrec_split_host", "split_host", "parser_only"],
        Some(FailureClass::TcpReset) => &[
            "baseline_current",
            "tlsrec_split_host",
            "tlsrec_hostfake_split",
            "split_host",
            "tlsrec_oob",
            "tlsrec_seqovl_midsld",
        ],
        Some(FailureClass::SilentDrop) if !fake_ttl_available => {
            &["baseline_current", "tlsrec_split_host", "tlsrec_hostfake_split", "split_host"]
        }
        Some(FailureClass::SilentDrop) => &[
            "baseline_current",
            "tlsrec_fake_rich",
            "tlsrec_disorder",
            "tlsrec_hostfake_split",
            "tlsrec_fakeddisorder",
        ],
        Some(FailureClass::TlsAlert) => &[
            "baseline_current",
            "tlsrec_split_host",
            "tlsrec_hostfake_split",
            "tlsrec_fake_hrr",
            "split_host",
            "tlsrec_seqovl_midsld",
        ],
        _ => &[
            "baseline_current",
            "tlsrec_split_host",
            "tlsrec_hostfake_split",
            "tlsrec_fake_rich",
            "tlsrec_disorder",
            "split_host",
            "tlsrec_oob",
            "tlsrandrec_split",
        ],
    };
    let mut ordered = Vec::with_capacity(candidates.len());
    for id in preferred_ids {
        if let Some(candidate) = candidates.iter().find(|candidate| candidate.id == *id) {
            ordered.push(candidate.clone());
        }
    }
    for candidate in candidates {
        if !ordered.iter().any(|existing| existing.id == candidate.id) {
            ordered.push(candidate.clone());
        }
    }
    ordered
}

pub fn filter_quic_candidates_for_failure(
    candidates: Vec<StrategyCandidateSpec>,
    failure_class: Option<FailureClass>,
) -> Vec<StrategyCandidateSpec> {
    if !matches!(failure_class, Some(FailureClass::QuicBreakage)) {
        return candidates;
    }
    let allowed = [
        "quic_compat_burst",
        "quic_realistic_burst",
        "quic_multi_initial_realistic",
        "quic_sni_split",
        "quic_crypto_split",
        "quic_padding_ladder",
        "quic_cid_churn",
        "quic_packet_number_gap",
        "quic_version_negotiation_decoy",
        "quic_fake_version",
        "quic_dummy_prepend",
        "quic_ipfrag2",
        "quic_ipfrag2_hopbyhop",
        "quic_ipfrag2_hopbyhop2",
        "quic_ipfrag2_destopt",
        "quic_ipfrag2_hopbyhop_destopt",
        "quic_disabled",
    ];
    candidates.into_iter().filter(|candidate| allowed.contains(&candidate.id)).collect()
}

pub fn interleave_candidate_families(
    mut candidates: Vec<StrategyCandidateSpec>,
    seed: u64,
) -> Vec<StrategyCandidateSpec> {
    let mut families = BTreeMap::<&'static str, Vec<StrategyCandidateSpec>>::new();
    for candidate in candidates.drain(..) {
        families.entry(candidate.family).or_default().push(candidate);
    }
    let mut family_order = families.keys().copied().collect::<Vec<_>>();
    family_order.sort_by_key(|family| stable_probe_hash(seed, family));
    for family in &family_order {
        if let Some(entries) = families.get_mut(family) {
            entries.sort_by_key(|candidate| stable_probe_hash(seed, candidate.id));
        }
    }
    let mut ordered = Vec::new();
    loop {
        let mut progressed = false;
        for family in &family_order {
            let Some(entries) = families.get_mut(family) else {
                continue;
            };
            if entries.is_empty() {
                continue;
            }
            ordered.push(entries.remove(0));
            progressed = true;
        }
        if !progressed {
            break;
        }
    }
    ordered
}

pub fn next_candidate_index(candidates: &[StrategyCandidateSpec], blocked_family: Option<&str>) -> usize {
    blocked_family.and_then(|blocked| candidates.iter().position(|candidate| candidate.family != blocked)).unwrap_or(0)
}
