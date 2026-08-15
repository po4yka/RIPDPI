mod dispatch;
mod http;
mod https;
mod quic;
mod support;
mod tcp;

use std::sync::Arc;

use super::runtime::{CandidateRuntimeLauncher, CandidateRuntimeSupervisor};

pub(crate) struct DefaultStrategyLaneExecutor {
    runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    supervisor: Arc<CandidateRuntimeSupervisor>,
}

impl DefaultStrategyLaneExecutor {
    pub(crate) fn new(
        runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
        supervisor: Arc<CandidateRuntimeSupervisor>,
    ) -> Self {
        Self { runtime_launcher, supervisor }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use super::*;
    use crate::execution::runtime::{
        CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher, PreparedCandidateRuntime,
    };
    use crate::types::DomainTarget;

    struct FailingRuntimeLauncher {
        starts: AtomicUsize,
    }

    impl FailingRuntimeLauncher {
        fn new() -> Self {
            Self { starts: AtomicUsize::new(0) }
        }

        fn starts(&self) -> usize {
            self.starts.load(Ordering::Relaxed)
        }
    }

    impl CandidateRuntimeLauncher for FailingRuntimeLauncher {
        fn start_candidate_runtime(
            &self,
            _prepared: PreparedCandidateRuntime,
        ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
            self.starts.fetch_add(1, Ordering::Relaxed);
            Err(CandidateRuntimeError::Launch("runtime unavailable".to_string()))
        }
    }

    #[test]
    fn execute_tcp_candidate_returns_failed_when_launcher_fails() {
        let launcher = FailingRuntimeLauncher::new();
        let spec = crate::candidates::candidate_spec(
            "test",
            "Test",
            "test",
            ripdpi_monitor_adapter::proxy_config::ProxyUiConfig::default(),
        );
        let targets = vec![DomainTarget {
            host: "example.test".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        }];
        let cancel = AtomicBool::new(false);
        let supervisor = CandidateRuntimeSupervisor::default();

        let execution = tcp::execute_tcp_candidate(
            &launcher,
            &spec,
            &targets,
            None,
            0,
            tcp::TcpCandidateExecutionContext {
                tls_verifier: None,
                keylog_path: None,
                cancel: &cancel,
                supervisor: &supervisor,
            },
        );

        assert_eq!(launcher.starts(), 1);
        assert_eq!(execution.summary.outcome, "failed");
        assert_eq!(execution.summary.rationale, "runtime unavailable");
        assert_eq!(execution.summary.total_targets, 2);
    }

    #[test]
    fn execute_quic_candidate_without_targets_does_not_start_launcher() {
        let launcher = FailingRuntimeLauncher::new();
        let spec = crate::candidates::candidate_spec(
            "test",
            "Test",
            "test",
            ripdpi_monitor_adapter::proxy_config::ProxyUiConfig::default(),
        );
        let cancel = AtomicBool::new(false);
        let supervisor = CandidateRuntimeSupervisor::default();

        let execution = quic::execute_quic_candidate(&launcher, &spec, &[], None, 0, &cancel, &supervisor);

        assert_eq!(launcher.starts(), 0);
        assert_eq!(execution.summary.outcome, "not_applicable");
        assert_eq!(execution.summary.rationale, "No QUIC targets configured");
    }
}
