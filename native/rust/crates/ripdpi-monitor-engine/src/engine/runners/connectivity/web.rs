use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::connectivity::run_domain_probe_with_key_log;
use crate::engine::runtime::ExecutionPlan;
use crate::types::{DomainTarget, ProbeResult};

use super::support::ConnectivityProbeFamily;

pub(in crate::engine::runners) struct WebRunner;

struct WebFamily;

impl ConnectivityProbeFamily for WebFamily {
    type Target = DomainTarget;

    const PHASE: &'static str = "reachability";
    const ARTIFACT_SOURCE: &'static str = "domain_reachability";

    const DROP_INTERRUPTED_RESULT: bool = true;

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.domain_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("Reachability {}", target.host)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_domain_probe_with_key_log(
            target,
            &plan.transport,
            tls_verifier,
            plan.request.diagnostic_tls_keylog_path.as_deref(),
            Some(cancel),
        )
    }
}

impl_connectivity_runner!(WebRunner, WebFamily, Web);

#[cfg(test)]
pub(super) const PHASE: &str = WebFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = WebFamily::ARTIFACT_SOURCE;

#[cfg(test)]
mod interrupted_probe_tests {
    use std::io::{ErrorKind, Read};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use super::WebRunner;
    use crate::engine::plan::build_execution_plan;
    use crate::engine::runtime::{ExecutionCoordinator, ExecutionRuntime, ExecutionStageId, RunnerOutcome};
    use crate::transport::direct_transport;
    use crate::types::{DomainTarget, ScanRequest, SharedState};

    #[test]
    fn interrupted_web_probe_is_omitted_from_network_evidence() {
        for cancel_on_client_hello in [false, true] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let port = listener.local_addr().unwrap().port();
            let mut request: ScanRequest = serde_json::from_str(
                r#"{"profileId":"web","displayName":"Web","pathMode":"RAW_PATH","domainTargets":[],"dnsTargets":[],"tcpTargets":[],"whitelistSni":[]}"#,
            ).unwrap();
            request.domain_targets.push(DomainTarget {
                host: "localhost".into(),
                connect_ip: Some("127.0.0.1".into()),
                connect_ips: Vec::new(),
                https_port: Some(port),
                http_port: Some(port),
                http_path: "/".into(),
                is_control: false,
                concurrency_probe: None,
            });
            let mut plan = build_execution_plan("test-web".into(), request, 0, direct_transport()).unwrap();
            plan.stage_order = vec![ExecutionStageId::Web];
            plan.total_steps = 1;

            let cancel = Arc::new(AtomicBool::new(false));
            let mut runtime = ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), cancel.clone());
            let coordinator = ExecutionCoordinator::new(vec![Box::new(WebRunner)]);
            std::thread::scope(|scope| {
                let peer = scope.spawn(move || {
                    let cutoff = Instant::now() + Duration::from_secs(2);
                    let mut socket = loop {
                        match listener.accept() {
                            Ok((socket, _)) => break socket,
                            Err(err) if err.kind() == ErrorKind::WouldBlock && Instant::now() < cutoff => {
                                std::thread::sleep(Duration::from_millis(5));
                            }
                            Err(err) => panic!("no loopback TLS client reached the peer: {err}"),
                        }
                    };
                    socket.set_nonblocking(false).unwrap();
                    socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                    let mut content_type = [0];
                    socket.read_exact(&mut content_type).unwrap();
                    assert_eq!(content_type, [0x16]);
                    let interrupted_at = Instant::now();
                    if cancel_on_client_hello {
                        cancel.store(true, Ordering::Release);
                    }
                    let mut leftover = Vec::new();
                    socket.read_to_end(&mut leftover).unwrap();
                    interrupted_at.elapsed()
                });
                runtime.set_scan_deadline(
                    Instant::now()
                        + if cancel_on_client_hello { Duration::from_secs(3) } else { Duration::from_millis(600) },
                );
                assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Cancelled));
                let peer_wait = peer.join().unwrap();
                assert!(runtime.results.is_empty(), "administrative stop entered network evidence");
                let stage = &runtime.stage_executions()[0];
                assert_eq!(stage.executed_steps, 0);
                if cancel_on_client_hello {
                    assert!(peer_wait < Duration::from_millis(900), "cancel left TLS waiting: {peer_wait:?}");
                } else {
                    assert_eq!(stage.skipped_by_global_deadline_steps, 1);
                }
            });
        }
    }
}
