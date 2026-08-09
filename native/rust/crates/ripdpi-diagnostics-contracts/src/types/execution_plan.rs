use serde::{Deserialize, Serialize};

use crate::types::{DiagnosticProfileFamily, ProbeTaskFamily, ScanKind, ScanPathMode, StrategyEmitterTier};

pub const EXECUTION_PLAN_VERSION: &str = "execution_plan_v1";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionPlanSnapshot {
    pub plan_version: String,
    pub scan_kind: ScanKind,
    pub profile_family: DiagnosticProfileFamily,
    pub path_mode: ScanPathMode,
    pub transport_kind: String,
    pub stage_order: Vec<String>,
    pub total_steps: usize,
    pub scan_deadline_ms: u64,
    #[serde(default)]
    pub pack_refs: Vec<String>,
    #[serde(default)]
    pub probe_task_families: Vec<ProbeTaskFamily>,
    pub target_counts: ExecutionPlanTargetCounts,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy: Option<StrategyExecutionPlanSnapshot>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionPlanTargetCounts {
    pub domain_target_count: usize,
    pub dns_target_count: usize,
    pub tcp_target_count: usize,
    pub quic_target_count: usize,
    pub service_target_count: usize,
    pub circumvention_target_count: usize,
    pub throughput_target_count: usize,
    pub whitelist_sni_count: usize,
    pub telegram_target_count: usize,
    pub strategy_selected_domain_count: usize,
    pub strategy_selected_quic_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyExecutionPlanSnapshot {
    pub suite_id: String,
    pub inventory_semantics: String,
    pub probe_seed: String,
    pub max_candidates: Option<usize>,
    pub tcp_candidates: Vec<StrategyCandidatePlanSnapshot>,
    pub quic_candidates: Vec<StrategyCandidatePlanSnapshot>,
    pub short_circuit_hostfake: bool,
    pub short_circuit_quic_burst: bool,
    pub family_failure_threshold: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyCandidatePlanSnapshot {
    pub id: String,
    pub label: String,
    pub family: String,
    pub emitter_tier: StrategyEmitterTier,
    pub exact_emitter_requires_root: bool,
    pub approximate_fallback_family: Option<String>,
    pub quic_layout_family: Option<String>,
    pub eligibility: String,
    pub warmup: String,
    pub preserve_adaptive_fake_ttl: bool,
    pub requires_fake_ttl: bool,
    pub requires_tcp_fast_open: bool,
    #[serde(default)]
    pub required_capabilities: Vec<String>,
}
