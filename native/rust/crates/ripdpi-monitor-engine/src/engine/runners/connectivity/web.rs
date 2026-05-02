use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_domain_probe;
use crate::engine::runtime::{CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{DomainTarget, ProbeResult};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

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
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_domain_probe(target, &plan.transport, tls_verifier)
    }
}

impl ExecutionStageRunner for WebRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Web
    }

    fn phase(&self) -> &'static str {
        WebFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<WebFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        collect_family_steps::<WebFamily>(plan, cancel, tls_verifier)
    }
}
