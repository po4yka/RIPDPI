use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{ProbeExecutionContext, run_throughput_probe_with_progress};
use crate::engine::runtime::ExecutionPlan;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{ProbeResult, ThroughputTarget};

use super::super::support::ConnectivityProbeFamily;

pub(super) struct ThroughputFamily;

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
        _probe_context: &ProbeExecutionContext,
        cancel: &std::sync::atomic::AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        run_throughput_probe_with_progress(target, &plan.transport, key_log.as_ref(), tls_verifier, cancel, &mut |_| {})
    }
}
