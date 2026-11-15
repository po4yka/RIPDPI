use std::net::UdpSocket;
use std::sync::atomic::Ordering;
use std::time::Instant;

use super::*;
use crate::classification::interleave_candidate_families;
use crate::types::QuicTarget;
use crate::util::stable_probe_hash;

fn blackhole_plan(port: u16, following_stage: bool) -> ExecutionPlan {
    let mut plan = bounded_plan("quick_v1", Some(1));
    let strategy = plan.strategy.as_mut().unwrap();
    let candidates = build_strategy_probe_suite(&strategy.suite_id, &ProxyUiConfig::default()).unwrap().quic_candidates;
    strategy.probe_seed = (0..1000)
        .find(|seed| {
            interleave_candidate_families(candidates.clone(), stable_probe_hash(*seed, "quic"))[0].id == "quic_disabled"
        })
        .expect("the unmodified QUIC control must be selectable");
    plan.request.quic_targets = vec![
        QuicTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: Vec::new(),
            port,
        };
        12
    ];
    plan.stage_order = vec![ExecutionStageId::StrategyQuicCandidates];
    if following_stage {
        plan.stage_order.push(ExecutionStageId::StrategyRecommendation);
    }
    plan
}

#[test]
fn quic_blackhole_stops_at_stage_or_global_deadline() {
    let _serial = lock_network_probes();
    for following_stage in [false, true] {
        let listener = UdpSocket::bind("127.0.0.1:0").unwrap();
        listener.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let mut plan = blackhole_plan(listener.local_addr().unwrap().port(), following_stage);
        let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
        plan.total_steps = coordinator.total_steps(&plan);
        let mut runtime = runtime();
        let started = Instant::now();
        runtime.set_scan_deadline(started + Duration::from_millis(600));

        let outcome = coordinator.run(&plan, &mut runtime, None);

        assert!(started.elapsed() < Duration::from_secs(1), "elapsed {:?}", started.elapsed());
        let mut initial = [0u8; 2048];
        assert!(listener.recv(&mut initial).unwrap() >= 1200);
        let stages = runtime.stage_executions();
        let stage = &stages[0];
        assert_eq!(stage.executed_steps, 0);
        if following_stage {
            assert!(matches!(outcome, RunnerOutcome::Completed));
            assert_eq!(stage.skipped_by_stage_budget_steps, stage.planned_steps);
            assert_eq!(stage.skipped_by_global_deadline_steps, 0);
            assert_eq!(stages[1].skipped_by_global_deadline_steps, 0);
        } else {
            assert!(matches!(outcome, RunnerOutcome::Cancelled));
            assert_eq!(stage.skipped_by_global_deadline_steps, stage.planned_steps);
            assert_eq!(stage.skipped_by_stage_budget_steps, 0);
        }
        assert!(runtime.strategy.quic_candidates.is_empty());
        assert!(runtime.results.iter().all(|result| result.probe_type != "strategy_quic"));
    }
}

#[test]
fn quic_receive_stops_on_user_cancel() {
    let _serial = lock_network_probes();
    let listener = UdpSocket::bind("127.0.0.1:0").unwrap();
    listener.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let mut plan = blackhole_plan(listener.local_addr().unwrap().port(), true);
    let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
    plan.total_steps = coordinator.total_steps(&plan);
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::clone(&cancel));
    let started = Instant::now();
    runtime.set_scan_deadline(started + Duration::from_secs(4));
    let outcome = std::thread::scope(|scope| {
        scope.spawn(|| {
            let mut initial = [0u8; 2048];
            assert!(listener.recv(&mut initial).unwrap() >= 1200);
            cancel.store(true, Ordering::Release);
        });
        coordinator.run(&plan, &mut runtime, None)
    });

    assert!(matches!(outcome, RunnerOutcome::Cancelled));
    assert!(started.elapsed() < Duration::from_secs(1), "elapsed {:?}", started.elapsed());
    assert!(runtime.strategy.quic_candidates.is_empty());
    assert!(runtime.results.is_empty());
}
