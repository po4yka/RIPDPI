use crate::candidates::StrategyCandidateSpec;
use crate::types::ProbeResult;

/// Split-probing signal consumer: the HTTPS baseline saw exactly one TLS
/// version reachable while the other was blocked (the `tls_version_split`
/// outcome -- see the per-version probe attempts in the HTTPS lane). That is
/// evidence of version-sensitive, SNI-based blocking, for which the
/// ClientHello/TLS-record splitting families are the natural first responders.
pub(in crate::engine::runners::strategy) fn baseline_has_tls_version_split(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "strategy_https" && result.outcome == "tls_version_split")
}

/// SNI-splitting follow-up families, promoted first when the baseline reports a
/// `tls_version_split`. Order mirrors the split preference in
/// `reorder_tcp_candidates_for_failure`. Promotion is keyed by id (these span
/// the `tlsrec_split`, `hostfake`, and `split` families), matching the id-list
/// approach that function already uses.
const TLS_VERSION_SPLIT_PREFERRED_IDS: &[&str] = &["tlsrec_split_host", "tlsrec_hostfake_split", "split_host"];

/// Float the SNI-splitting families to the front (in the preferred order),
/// preserving the relative order of every other spec. Stable and seed-free.
pub(super) fn promote_tls_version_split_families(specs: Vec<StrategyCandidateSpec>) -> Vec<StrategyCandidateSpec> {
    let mut promoted = Vec::with_capacity(specs.len());
    for id in TLS_VERSION_SPLIT_PREFERRED_IDS {
        if let Some(spec) = specs.iter().find(|spec| spec.id == *id) {
            promoted.push(spec.clone());
        }
    }
    for spec in specs {
        if !promoted.iter().any(|existing| existing.id == spec.id) {
            promoted.push(spec);
        }
    }
    promoted
}
