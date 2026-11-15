use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use crate::engine::runtime::ExecutionRuntime;
use crate::types::{ProbeDetail, ProbeResult, ScanCompletionKind, ScanReportDisposition, SharedState};

#[test]
fn telegram_checkpoint_preserves_completed_legs_without_appending_a_terminal_result() {
    let plan = super::test_plan();
    let mut state = ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::new(AtomicBool::new(false)));
    state.results.push(ProbeResult {
        probe_type: "dns_integrity".into(),
        target: "example.test".into(),
        outcome: "ok".into(),
        details: vec![],
    });

    let mut details = Vec::new();
    for (key, bytes) in [("downloadBytes", "31"), ("uploadBytes", "22")] {
        details.push(ProbeDetail { key: key.into(), value: bytes.into() });
        let probe = ProbeResult {
            probe_type: "telegram_availability".into(),
            target: "telegram.org".into(),
            outcome: "partial".into(),
            details: details.clone(),
        };
        state.publish_probe_checkpoint(&plan, probe);
        let shared = state.shared.lock().expect("checkpoint state");
        let report = shared.checkpoint_report.as_ref().expect("checkpoint report");
        assert_eq!(report.report_disposition, ScanReportDisposition::Checkpoint);
        assert_eq!(report.completion_kind, ScanCompletionKind::PartialResults);
        assert_eq!(report.results.len(), 2);
        assert_eq!(report.results[0].target, "example.test");
        let observed: Vec<_> = report.results[1].details.iter().map(|item| (&item.key, &item.value)).collect();
        let expected: Vec<_> = details.iter().map(|item| (&item.key, &item.value)).collect();
        assert_eq!(observed, expected);
        assert!(report.observations.iter().any(|fact| fact.telegram.is_some()));
    }
    assert_eq!(state.completed_steps, 0);
    assert_eq!(state.results.len(), 1);
    assert!(state.observations.is_empty());
}
