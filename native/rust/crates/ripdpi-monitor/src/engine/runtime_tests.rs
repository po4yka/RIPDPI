use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use super::{ExecutionPlan, ExecutionRuntime, ExecutionStageId};
use crate::transport::direct_transport;
use crate::types::{
    DiagnosticProfileFamily, ScanKind, ScanPathMode, ScanRequest, SharedState, StrategyProbeProgressLane,
};

#[test]
fn parallel_group_contains_expected_stages() {
    // These are the stages run concurrently for CONNECTIVITY scans.
    // If this list changes, update ExecutionCoordinator::run() accordingly.
    let parallel_group: &[ExecutionStageId] = &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];
    assert!(parallel_group.contains(&ExecutionStageId::Dns));
    assert!(parallel_group.contains(&ExecutionStageId::Tcp));
    assert!(parallel_group.contains(&ExecutionStageId::Quic));
    assert!(!parallel_group.contains(&ExecutionStageId::Web));
    assert!(!parallel_group.contains(&ExecutionStageId::Service));
    assert!(!parallel_group.contains(&ExecutionStageId::StrategyTcpCandidates));
}

fn test_plan() -> ExecutionPlan {
    ExecutionPlan {
        session_id: "session-1".to_string(),
        request: ScanRequest {
            profile_id: "automatic-probing".to_string(),
            display_name: "Automatic probing".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::StrategyProbe,
            family: DiagnosticProfileFamily::AutomaticProbing,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            probe_tasks: Vec::new(),
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
        },
        started_at: 0,
        total_steps: 8,
        transport: direct_transport(),
        stage_order: Vec::new(),
        strategy: None,
    }
}

#[test]
fn skipped_strategy_probe_candidate_publishes_live_progress_and_increments_step() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    let plan = test_plan();

    runtime.record_skipped_strategy_probe_candidate(
        &plan,
        "tcp",
        StrategyProbeProgressLane::Tcp,
        3,
        14,
        "tcp_fake_tls",
        "TCP fake TLS",
        Some("skipped".to_string()),
        "Skipped TCP fake TLS".to_string(),
    );

    let progress = shared.lock().expect("shared").progress.clone().expect("progress");
    let live_progress = progress.strategy_probe_progress.expect("strategy probe progress");

    assert_eq!(progress.completed_steps, 1);
    assert_eq!(progress.phase, "tcp");
    assert_eq!(progress.message, "Skipped TCP fake TLS");
    assert_eq!(progress.latest_probe_target.as_deref(), Some("TCP fake TLS"));
    assert_eq!(progress.latest_probe_outcome.as_deref(), Some("skipped"));
    assert_eq!(live_progress.lane, StrategyProbeProgressLane::Tcp);
    assert_eq!(live_progress.candidate_index, 3);
    assert_eq!(live_progress.candidate_total, 14);
    assert_eq!(live_progress.candidate_id, "tcp_fake_tls");
    assert_eq!(live_progress.candidate_label, "TCP fake TLS");
}
