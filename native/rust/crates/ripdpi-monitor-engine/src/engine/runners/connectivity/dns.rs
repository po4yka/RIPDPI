use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_dns_probe;
use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{DnsTarget, ProbeResult};

use super::support::{collect_family_steps, target_count, ConnectivityProbeFamily};

pub(in crate::engine::runners) struct DnsRunner;

struct DnsFamily;

impl ConnectivityProbeFamily for DnsFamily {
    type Target = DnsTarget;

    const PHASE: &'static str = "dns";
    const ARTIFACT_SOURCE: &'static str = "dns_integrity";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.dns_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("DNS {}", target.domain)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_dns_probe(target, &plan.transport, &plan.request.path_mode)
    }
}

impl ExecutionStageRunner for DnsRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Dns
    }

    fn phase(&self) -> &'static str {
        DnsFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<DnsFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        collect_family_steps::<DnsFamily>(plan, cancel, tls_verifier)
    }
}
