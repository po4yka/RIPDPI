use std::sync::{Arc, atomic::AtomicBool};

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{DomainTarget, QuicTarget};

use super::scoring::CandidateExecution;

pub(crate) trait StrategyLaneExecutor: Send + Sync {
    fn execute_tcp_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[DomainTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        keylog_path: Option<&str>,
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
