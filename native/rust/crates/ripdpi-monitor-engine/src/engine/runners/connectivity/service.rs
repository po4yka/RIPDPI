use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_service_probe;
use crate::engine::runtime::{CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{ProbeResult, ServiceTarget};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

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
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_service_probe(target, &plan.transport, tls_verifier)
    }
}

impl ExecutionStageRunner for ServiceRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Service
    }

    fn phase(&self) -> &'static str {
        ServiceFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<ServiceFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        collect_family_steps::<ServiceFamily>(plan, cancel, tls_verifier)
    }
}
