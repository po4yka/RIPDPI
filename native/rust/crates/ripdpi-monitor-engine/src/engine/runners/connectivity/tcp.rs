use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_tcp_probe;
use crate::engine::runtime::{CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{ProbeResult, TcpTarget};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

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
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_tcp_probe(target, &plan.request.whitelist_sni, &plan.transport)
    }
}

impl ExecutionStageRunner for TcpRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Tcp
    }

    fn phase(&self) -> &'static str {
        TcpFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<TcpFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        collect_family_steps::<TcpFamily>(plan, cancel, tls_verifier)
    }
}
