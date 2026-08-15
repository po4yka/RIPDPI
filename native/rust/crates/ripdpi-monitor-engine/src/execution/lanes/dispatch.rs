use std::sync::{Arc, atomic::AtomicBool};

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{DomainTarget, QuicTarget};

use super::super::runner_contract::StrategyLaneExecutor;
use super::super::scoring::CandidateExecution;
use super::{DefaultStrategyLaneExecutor, quic, tcp};

impl StrategyLaneExecutor for DefaultStrategyLaneExecutor {
    fn execute_tcp_candidate(
        &self,
        spec: &StrategyCandidateSpec,
        targets: &[DomainTarget],
        runtime_context: Option<&ProxyRuntimeContext>,
        probe_seed: u64,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        keylog_path: Option<&str>,
        cancel: &AtomicBool,
    ) -> CandidateExecution {
        tcp::execute_tcp_candidate(
            self.runtime_launcher.as_ref(),
            spec,
            targets,
            runtime_context,
            probe_seed,
            tcp::TcpCandidateExecutionContext {
                tls_verifier,
                keylog_path,
                cancel,
                supervisor: self.supervisor.as_ref(),
            },
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
        quic::execute_quic_candidate(
            self.runtime_launcher.as_ref(),
            spec,
            targets,
            runtime_context,
            probe_seed,
            cancel,
            self.supervisor.as_ref(),
        )
    }
}
