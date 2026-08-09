mod attempt_recording;
mod connection_concurrency;
mod dns_baseline;
mod quic;
mod recommendation;
mod support;
mod tcp;

pub(super) use connection_concurrency::StrategyConnectionConcurrencyRunner;
pub(super) use dns_baseline::StrategyDnsBaselineRunner;
pub(super) use quic::StrategyQuicRunner;
pub(super) use recommendation::StrategyRecommendationRunner;
pub(in crate::engine) use recommendation::prepare_strategy_probe_report;
pub(super) use tcp::StrategyTcpRunner;

#[cfg(test)]
use support::{
    FamilyFailureTracker, baseline_supports_ech_candidates, ordered_follow_up_tcp_candidates, pilot_bucket_label,
    resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment, select_promotable_candidate_index,
    select_safe_or_baseline_candidate_index, stratified_pilot_targets,
};
#[cfg(test)]
use support::{baseline_has_tls_ech_only, baseline_has_tls_version_split};

#[cfg(test)]
mod tests;
