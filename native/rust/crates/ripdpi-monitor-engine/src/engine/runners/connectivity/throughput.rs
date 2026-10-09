mod family;
mod live;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::{
    CollectedStageOutcome, ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome,
};

use self::family::ThroughputFamily;
use super::support::ConnectivityProbeFamily;

pub(in crate::engine::runners) struct ThroughputRunner;

impl ExecutionStageRunner for ThroughputRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Throughput
    }
    fn phase(&self) -> &'static str {
        ThroughputFamily::PHASE
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.request.throughput_targets.len()
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        super::support::collect_family_steps::<ThroughputFamily>(plan, cancel, tls_verifier)
    }
    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        live::run(plan, runtime, tls_verifier)
    }
}

#[cfg(test)]
pub(super) const PHASE: &str = ThroughputFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = ThroughputFamily::ARTIFACT_SOURCE;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::SharedState;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    };
    use std::time::{Duration, Instant};

    #[test]
    fn transfer_progress_is_live_and_cleared_after_cancellation() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
        let address = listener.local_addr().expect("address");
        let (stop_server, wait_server) = mpsc::channel();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept");
            let mut request = [0; 1024];
            assert!(socket.read(&mut request).expect("request") > 0);
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nabc").expect("partial response");
            let _ = wait_server.recv_timeout(Duration::from_secs(4));
        });
        let request = serde_json::from_value(serde_json::json!({
            "profileId":"transfer-test", "displayName":"Transfer", "pathMode":"RAW_PATH",
            "domainTargets":[], "dnsTargets":[], "tcpTargets":[], "whitelistSni":[],
            "throughputTargets":[{"id":"fixture", "label":"Fixture", "url":format!("http://127.0.0.1:{}/", address.port()), "windowBytes":100, "runs":3}]
        })).expect("request");
        let mut plan = crate::engine::plan::build_execution_plan(
            "transfer-test".into(),
            request,
            0,
            crate::transport::direct_transport(),
        )
        .expect("plan");
        plan.total_steps = 1;
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let cancel = Arc::new(AtomicBool::new(false));
        let mut runtime = ExecutionRuntime::new(shared.clone(), cancel.clone());
        let worker = std::thread::spawn(move || {
            let outcome = ThroughputRunner.run(&plan, &mut runtime, None);
            assert!(matches!(outcome, RunnerOutcome::Cancelled));
            runtime
        });
        let deadline = Instant::now() + Duration::from_secs(3);
        let mut saw_live_bytes = false;
        while Instant::now() < deadline {
            let progress = shared.lock().expect("state").progress.clone();
            if progress.and_then(|progress| progress.transfer_progress).is_some_and(|progress| {
                progress.measurement.received_body_byte_count == 3 && progress.measurement.termination_reason.is_none()
            }) {
                saw_live_bytes = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        cancel.store(true, Ordering::Release);
        let runtime = worker.join().expect("worker");
        stop_server.send(()).expect("release server");
        server.join().expect("server");
        assert!(saw_live_bytes, "partial bytes must be visible before the probe returns");
        assert!(shared.lock().expect("state").progress.as_ref().expect("progress").transfer_progress.is_none());
        let evidence =
            runtime.results[0].details.iter().find(|detail| detail.key == "transferEvidence").expect("evidence");
        let evidence: crate::types::TransferEvidence = serde_json::from_str(&evidence.value).expect("decode");
        assert_eq!(evidence.runs.len(), 1, "cancel skips remaining runs");
        assert_eq!(evidence.runs[0].received_body_byte_count, 3);
        assert_eq!(evidence.runs[0].termination_reason.as_deref(), Some("cancelled"));
    }
}
