use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use ripdpi_proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{DomainTarget, QuicTarget};

use super::lanes::{execute_quic_candidate, execute_tcp_candidate};
use super::runtime::CandidateRuntimeLauncher;
use super::scoring::CandidateExecution;

pub(crate) trait StrategyLaneExecutor: Send + Sync {
    fn execute_tcp_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[DomainTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        cancel: &AtomicBool,
    ) -> CandidateExecution;

    fn execute_quic_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[QuicTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        cancel: &AtomicBool,
    ) -> CandidateExecution;
}

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
