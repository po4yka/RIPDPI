use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::connectivity::run_circumvention_probe;
use crate::engine::runtime::ExecutionPlan;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{CircumventionTarget, ProbeResult};

use super::support::ConnectivityProbeFamily;

pub(in crate::engine::runners) struct CircumventionRunner;

struct CircumventionFamily;

impl ConnectivityProbeFamily for CircumventionFamily {
    type Target = CircumventionTarget;

    const PHASE: &'static str = "circumvention";
    const ARTIFACT_SOURCE: &'static str = "circumvention_reachability";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.circumvention_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("Circumvention {}", target.tool)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        _cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_circumvention_probe(target, &plan.transport, tls_verifier, key_log.as_ref())
    }
}

impl_connectivity_runner!(CircumventionRunner, CircumventionFamily, Circumvention);

#[cfg(test)]
pub(super) const PHASE: &str = CircumventionFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = CircumventionFamily::ARTIFACT_SOURCE;
