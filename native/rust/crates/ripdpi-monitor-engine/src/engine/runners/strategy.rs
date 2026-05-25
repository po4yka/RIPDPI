mod dns_baseline;
mod quic;
mod recommendation;
mod support;
mod tcp;

pub(super) use dns_baseline::StrategyDnsBaselineRunner;
pub(super) use quic::StrategyQuicRunner;
pub(in crate::engine) use recommendation::prepare_strategy_probe_report;
pub(super) use recommendation::StrategyRecommendationRunner;
pub(super) use tcp::StrategyTcpRunner;

#[cfg(test)]
use support::baseline_has_tls_ech_only;
#[cfg(test)]
use support::{
    baseline_supports_ech_candidates, ordered_follow_up_tcp_candidates, pilot_bucket_label,
    resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment, select_promotable_candidate_index,
    stratified_pilot_targets, FamilyFailureTracker,
};

#[cfg(test)]
mod tests;
