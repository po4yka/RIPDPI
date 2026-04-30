use crate::candidates::prelude::*;

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
