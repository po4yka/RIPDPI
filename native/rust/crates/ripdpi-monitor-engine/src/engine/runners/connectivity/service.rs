use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::connectivity::run_service_probe;
use crate::engine::runtime::ExecutionPlan;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{ProbeResult, ServiceTarget};

use super::support::ConnectivityProbeFamily;

pub(in crate::engine::runners) struct ServiceRunner;

struct ServiceFamily;

impl ConnectivityProbeFamily for ServiceFamily {
    type Target = ServiceTarget;

    const PHASE: &'static str = "service";
    const ARTIFACT_SOURCE: &'static str = "service_reachability";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.service_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("Service {}", target.service)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        _cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_service_probe(target, &plan.transport, tls_verifier, key_log.as_ref())
    }
}

impl_connectivity_runner!(ServiceRunner, ServiceFamily, Service);

#[cfg(test)]
pub(super) const PHASE: &str = ServiceFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = ServiceFamily::ARTIFACT_SOURCE;
