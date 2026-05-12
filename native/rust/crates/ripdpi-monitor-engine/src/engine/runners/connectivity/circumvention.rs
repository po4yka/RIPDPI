use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_circumvention_probe;
use crate::engine::runtime::{CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{CircumventionTarget, ProbeResult};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

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
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_circumvention_probe(target, &plan.transport, tls_verifier, key_log.as_ref())
    }
}

impl ExecutionStageRunner for CircumventionRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Circumvention
    }

    fn phase(&self) -> &'static str {
        CircumventionFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<CircumventionFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        collect_family_steps::<CircumventionFamily>(plan, cancel, tls_verifier)
    }
}
