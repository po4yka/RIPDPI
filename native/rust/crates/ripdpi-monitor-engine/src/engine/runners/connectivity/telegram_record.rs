use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use crate::telegram::run_telegram_probe_with_abort;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::TelegramTarget;

pub(super) fn record_telegram_probe(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    target: &TelegramTarget,
    phase: &'static str,
) {
    let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
    let deadline = runtime.stage_deadline().or_else(|| runtime.scan_deadline());
    let cancel = runtime.cancel_token();
    let should_abort = || telegram_abort_reason(cancel, deadline);
    let probe = ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
        run_telegram_probe_with_abort(target, &plan.transport, key_log.as_ref(), &should_abort)
    });
    let outcome = probe.outcome.clone();
    let artifacts = RunnerArtifacts::from_probe(probe, "telegram", &plan.request.path_mode);
    runtime.record_step(
        plan,
        phase,
        "Telegram availability checked".to_string(),
        Some("telegram.org".to_string()),
        Some(outcome),
        None,
        artifacts,
    );
}

fn telegram_abort_reason(cancel: &AtomicBool, deadline: Option<Instant>) -> Option<&'static str> {
    // Ordering: Acquire observes cancellation published by the session controller before starting new Telegram I/O.
    if cancel.load(Ordering::Acquire) {
        Some("cancelled")
    } else if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
        Some("deadline_exceeded")
    } else {
        None
    }
}
