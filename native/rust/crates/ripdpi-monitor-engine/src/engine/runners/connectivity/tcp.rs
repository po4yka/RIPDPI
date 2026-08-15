use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::connectivity::run_tcp_probe;
use crate::engine::runtime::ExecutionPlan;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{ProbeResult, TcpTarget};

use super::support::ConnectivityProbeFamily;

pub(in crate::engine::runners) struct TcpRunner;

struct TcpFamily;

impl ConnectivityProbeFamily for TcpFamily {
    type Target = TcpTarget;

    const PHASE: &'static str = "tcp";
    const ARTIFACT_SOURCE: &'static str = "tcp_fat_header";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.tcp_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("TCP {}", target.provider)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        _cancel: &std::sync::atomic::AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_tcp_probe(target, &plan.request.whitelist_sni, &plan.transport, key_log.as_ref())
    }
}

impl_connectivity_runner!(TcpRunner, TcpFamily, Tcp);

#[cfg(test)]
pub(super) const PHASE: &str = TcpFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = TcpFamily::ARTIFACT_SOURCE;
