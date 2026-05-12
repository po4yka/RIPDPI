use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_throughput_probe;
use crate::engine::runtime::{CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{ProbeResult, ThroughputTarget};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

pub(in crate::engine::runners) struct ThroughputRunner;

struct ThroughputFamily;

impl ConnectivityProbeFamily for ThroughputFamily {
    type Target = ThroughputTarget;

    const PHASE: &'static str = "throughput";
    const ARTIFACT_SOURCE: &'static str = "throughput_window";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.throughput_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("Throughput {}", target.label)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_throughput_probe(target, &plan.transport, key_log.as_ref())
    }
}

impl ExecutionStageRunner for ThroughputRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Throughput
    }

    fn phase(&self) -> &'static str {
        ThroughputFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<ThroughputFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        collect_family_steps::<ThroughputFamily>(plan, cancel, tls_verifier)
    }
}
