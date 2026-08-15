use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::super::plan::ExecutionPlan;
use super::super::recording::record_collected_outcome;
use super::super::state::ExecutionRuntime;
use super::{ExecutionStageRunner, RunnerOutcome};

pub(super) fn run<Runner: ExecutionStageRunner + ?Sized>(
    runner: &Runner,
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> RunnerOutcome {
    let deadline = runtime.stage_deadline().or_else(|| runtime.scan_deadline());
    let collected = ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
        runner.run_collecting(plan, runtime.cancel_token(), tls_verifier)
    });
    record_collected_outcome(plan, runtime, collected)
}
