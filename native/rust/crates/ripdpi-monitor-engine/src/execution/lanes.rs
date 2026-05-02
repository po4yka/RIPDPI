mod http;
mod https;
mod quic;
mod support;
mod tcp;

use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use ripdpi_proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{DomainTarget, QuicTarget};

use super::runner_contract::StrategyLaneExecutor;
use super::runtime::CandidateRuntimeLauncher;
use super::scoring::CandidateExecution;

pub(crate) use quic::execute_quic_candidate;
pub(crate) use tcp::execute_tcp_candidate;

pub(crate) struct DefaultStrategyLaneExecutor {
    runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
}

impl DefaultStrategyLaneExecutor {
    pub(crate) fn new(runtime_launcher: Arc<dyn CandidateRuntimeLauncher>) -> Self {
        Self { runtime_launcher }
    }
}

impl StrategyLaneExecutor for DefaultStrategyLaneExecutor {
    fn execute_tcp_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[DomainTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        cancel: &AtomicBool,
    ) -> CandidateExecution {
        execute_tcp_candidate(
            self.runtime_launcher.as_ref(),
            spec,
            targets,
            runtime_context,
            probe_seed,
            tls_verifier,
            cancel,
        )
    }

    fn execute_quic_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[QuicTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        cancel: &AtomicBool,
    ) -> CandidateExecution {
        execute_quic_candidate(self.runtime_launcher.as_ref(), spec, targets, runtime_context, probe_seed, cancel)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use super::*;
    use crate::execution::runtime::CandidateRuntimeLauncher;
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
            _prepared: crate::execution::runtime::PreparedCandidateRuntime,
        ) -> Result<Box<dyn crate::execution::runtime::CandidateProbeRuntime>, String> {
            self.starts.fetch_add(1, Ordering::Relaxed);
            Err("runtime unavailable".to_string())
        }
    }

    #[test]
    fn execute_tcp_candidate_returns_failed_when_launcher_fails() {
        let launcher = FailingRuntimeLauncher::new();
        let spec =
            crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());
        let targets = vec![DomainTarget {
            host: "example.test".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        }];
        let cancel = AtomicBool::new(false);

        let execution = execute_tcp_candidate(&launcher, &spec, &targets, None, 0, None, &cancel);

        assert_eq!(launcher.starts(), 1);
        assert_eq!(execution.summary.outcome, "failed");
        assert_eq!(execution.summary.rationale, "runtime unavailable");
        assert_eq!(execution.summary.total_targets, 2);
    }

    #[test]
    fn execute_quic_candidate_without_targets_does_not_start_launcher() {
        let launcher = FailingRuntimeLauncher::new();
        let spec =
            crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());
        let cancel = AtomicBool::new(false);

        let execution = execute_quic_candidate(&launcher, &spec, &[], None, 0, &cancel);

        assert_eq!(launcher.starts(), 0);
        assert_eq!(execution.summary.outcome, "not_applicable");
        assert_eq!(execution.summary.rationale, "No QUIC targets configured");
    }
}
