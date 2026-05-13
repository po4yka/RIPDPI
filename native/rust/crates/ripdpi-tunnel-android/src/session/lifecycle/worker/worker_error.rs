use std::sync::Mutex;

use crate::telemetry::TunnelTelemetryState;

pub(super) fn record_worker_result(
    result: std::thread::Result<std::io::Result<()>>,
    telemetry: &TunnelTelemetryState,
    last_error: &Mutex<Option<String>>,
) {
    match result {
        Ok(Ok(())) => {}
        Ok(Err(err)) => {
            record_worker_error(telemetry, last_error, format!("worker exited with error: {err}"), err.to_string());
        }
        Err(panic) => {
            let msg = panic_message(panic.as_ref());
            record_worker_error(
                telemetry,
                last_error,
                format!("worker panicked: {msg}"),
                format!("Tunnel worker panicked: {msg}"),
            );
        }
    }
}

fn record_worker_error(
    telemetry: &TunnelTelemetryState,
    last_error: &Mutex<Option<String>>,
    log_message: String,
    stored_message: String,
) {
    telemetry.log_line("worker", "error", &log_message);
    let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = Some(stored_message.clone());
    drop(guard);
    telemetry.record_error(stored_message);
}

fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = panic.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = panic.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}
