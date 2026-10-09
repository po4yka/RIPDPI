use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{run_throughput_probe_with_progress, set_progress};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts, RunnerOutcome};
use crate::tls::tls_key_log_callback_for_path;
use crate::types::{ScanProgress, TransferProgress};

use super::super::support::ConnectivityProbeFamily;
use super::family::ThroughputFamily;

pub(super) fn run(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> RunnerOutcome {
    for target in &plan.request.throughput_targets {
        if runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline() {
            return RunnerOutcome::Cancelled;
        }
        let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
        let deadline = runtime.stage_deadline().or_else(|| runtime.scan_deadline());
        let probe = crate::util::with_scan_io_deadline(deadline, || {
            run_throughput_probe_with_progress(
                target,
                &plan.transport,
                key_log.as_ref(),
                tls_verifier,
                runtime.cancel_token(),
                &mut |measurement| {
                    set_progress(
                        &runtime.shared,
                        ScanProgress {
                            session_id: plan.session_id.clone(),
                            phase: ThroughputFamily::PHASE.to_string(),
                            completed_steps: runtime.completed_steps,
                            total_steps: plan.total_steps,
                            message: ThroughputFamily::message(target),
                            is_finished: false,
                            latest_probe_target: Some(target.label.clone()),
                            latest_probe_outcome: None,
                            strategy_probe_progress: None,
                            transfer_progress: Some(TransferProgress {
                                target: target.label.clone(),
                                measurement: measurement.clone(),
                            }),
                        },
                    );
                },
            )
        });
        let outcome = Some(probe.outcome.clone());
        runtime.record_step(
            plan,
            ThroughputFamily::PHASE,
            ThroughputFamily::message(target),
            Some(target.label.clone()),
            outcome,
            None,
            RunnerArtifacts::from_probe(probe, ThroughputFamily::ARTIFACT_SOURCE, &plan.request.path_mode),
        );
        if runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline() {
            return RunnerOutcome::Cancelled;
        }
    }
    RunnerOutcome::Completed
}
