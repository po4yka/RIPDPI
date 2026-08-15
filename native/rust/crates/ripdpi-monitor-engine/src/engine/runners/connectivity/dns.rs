use std::sync::{Arc, atomic::AtomicBool};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{ProbeExecutionContext, run_dns_probe_with_context};
use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{DnsTarget, ProbeResult};

use super::support::{ConnectivityProbeFamily, collect_family_steps, target_count};

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
        probe_context: &ProbeExecutionContext,
        cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        run_dns_probe_with_context(target, probe_context, &plan.request.path_mode, cancel)
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
