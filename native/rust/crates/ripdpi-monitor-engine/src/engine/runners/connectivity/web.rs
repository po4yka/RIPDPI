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
        _cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_domain_probe_with_key_log(
            target,
            &plan.transport,
            tls_verifier,
            plan.request.diagnostic_tls_keylog_path.as_deref(),
        )
    }
}

impl_connectivity_runner!(WebRunner, WebFamily, Web);

#[cfg(test)]
pub(super) const PHASE: &str = WebFamily::PHASE;
#[cfg(test)]
pub(super) const ARTIFACT_SOURCE: &str = WebFamily::ARTIFACT_SOURCE;
