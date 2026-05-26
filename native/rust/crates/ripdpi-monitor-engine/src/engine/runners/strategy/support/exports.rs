pub(in crate::engine::runners::strategy) use super::audit_assessment::resolve_strategy_probe_audit_assessment;
#[cfg(test)]
pub(in crate::engine::runners::strategy) use super::candidate_ordering::baseline_has_tls_ech_only;
pub(in crate::engine::runners::strategy) use super::candidate_ordering::{
    baseline_supports_ech_candidates, compute_rst_adaptive_timeout, ordered_follow_up_tcp_candidates,
    FamilyFailureTracker, ECH_ELIGIBILITY_RATIONALE, FAKE_TTL_ELIGIBILITY_RATIONALE,
    TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
};
pub(in crate::engine::runners::strategy) use super::capabilities::{
    annotate_emitter_execution, capability_available, capability_suffix, missing_capability_rationale,
};
pub(in crate::engine::runners::strategy) use super::pilot_targets::{pilot_bucket_label, stratified_pilot_targets};
pub(in crate::engine::runners::strategy) use super::progress::{
    record_not_applicable_tcp_candidate, strategy_probe_live_progress_with_targets,
};
#[cfg(test)]
pub(in crate::engine::runners::strategy) use super::recommendation_config::select_promotable_candidate_index;
pub(in crate::engine::runners::strategy) use super::recommendation_config::{
    resolve_recommended_proxy_config_json, select_safe_or_baseline_candidate_index,
};
