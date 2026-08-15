use std::sync::{Arc, atomic::AtomicBool};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{ProbeExecutionContext, run_quic_probe};
use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{ProbeResult, QuicTarget};

use super::support::{ConnectivityProbeFamily, collect_family_steps, target_count};

pub(in crate::engine::runners) struct QuicRunner;

struct QuicFamily;

impl ConnectivityProbeFamily for QuicFamily {
    type Target = QuicTarget;

    const PHASE: &'static str = "quic";
    const ARTIFACT_SOURCE: &'static str = "quic_reachability";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.quic_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("QUIC {}", target.host)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        _cancel: &std::sync::atomic::AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_quic_probe(target, &plan.transport)
    }
}

impl ExecutionStageRunner for QuicRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Quic
    }

    fn phase(&self) -> &'static str {
        QuicFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<QuicFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        collect_family_steps::<QuicFamily>(plan, cancel, tls_verifier)
    }
}
