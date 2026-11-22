use super::*;
use crate::tests::DirectCandidateRuntimeLauncher;
use crate::types::DomainTarget;

#[test]
fn dns_baseline_completes_its_step_for_a_numeric_target() {
    let mut plan = strategy_test_plan();
    plan.request.domain_targets = vec![DomainTarget {
        host: "127.0.0.1".to_string(),
        connect_ip: None,
        connect_ips: Vec::new(),
        https_port: Some(443),
        http_port: None,
        http_path: "/".to_string(),
        is_control: false,
        control_tier: None,
        concurrency_probe: None,
    }];
    plan.stage_order = vec![ExecutionStageId::StrategyDnsBaseline];
    plan.total_steps = 1;
    let mut runtime = ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::new(AtomicBool::new(false)));
    let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
    assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
    assert_eq!(runtime.completed_steps, 1);
    assert_eq!(runtime.results.len(), 1);
    assert_eq!(runtime.results[0].outcome, "not_applicable");
    assert!(runtime.strategy.baseline_failure.is_none());
}
