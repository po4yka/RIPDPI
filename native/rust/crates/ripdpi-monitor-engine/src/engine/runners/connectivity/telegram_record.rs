use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use crate::telegram::run_telegram_probe;
use crate::tls::tls_key_log_callback_for_path;
use crate::types::TelegramTarget;

pub(super) fn record_telegram_probe(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    target: &TelegramTarget,
    phase: &'static str,
) {
    let key_log = plan.request.diagnostic_tls_keylog_path.as_deref().map(tls_key_log_callback_for_path);
    let probe = run_telegram_probe(target, &plan.transport, key_log.as_ref());
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
