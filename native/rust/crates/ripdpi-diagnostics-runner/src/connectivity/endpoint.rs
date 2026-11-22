mod http3_comparison;
mod matrix_body;
mod selective_matrix;
pub use selective_matrix::{run_selective_matrix_attempt, run_strategy_control_transfer};
mod quic_endpoint;
mod target_parse;
mod tcp_tls_endpoint;
mod throughput;
mod transfer;
mod types;
mod verified_upload;

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;

use crate::connectivity::adapters::http::{HttpObservation, try_http_request_targets_with_cancel};
use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::TransportConfig;

pub(super) use http3_comparison::run_http3_comparison;
pub(super) use quic_endpoint::run_quic_endpoint_probe;
pub(super) use tcp_tls_endpoint::run_endpoint_probe;
pub(super) use throughput::{measure_throughput_run, measure_throughput_with_options};
pub(super) use verified_upload::run_verified_upload;

pub(super) fn probe_http_url(
    url: &str,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: &AtomicBool,
) -> HttpObservation {
    if let Some(reason) = endpoint_stop_reason(cancel) {
        return HttpObservation { status: "interrupted".into(), response: None, error: Some(reason.into()) };
    }
    let parsed = match target_parse::parse_http_target(url, None, &[], None) {
        Ok(parsed) => parsed,
        Err(error) => return HttpObservation { status: "invalid_target".into(), response: None, error: Some(error) },
    };
    let mut observation = try_http_request_targets_with_cancel(
        &parsed.connect_targets,
        parsed.port,
        transport,
        &parsed.host,
        &parsed.path,
        parsed.secure,
        key_log,
        Some(cancel),
    );
    if observation.response.is_none()
        && let Some(reason) = endpoint_stop_reason(cancel)
    {
        observation.status = "interrupted".into();
        observation.error = Some(reason.into());
    }
    // A legal refusal still came from an HTTP peer. It says nothing about where the restriction originated.
    if observation.response.as_ref().is_some_and(|response| response.status_code == 451) {
        observation.status = "http_status_451".into();
    }
    observation
}

pub(super) fn endpoint_stop_reason(cancel: &AtomicBool) -> Option<&'static str> {
    // Acquire observes the cancellation flag from the scan owner.
    if cancel.load(Ordering::Acquire) {
        Some("scan_cancelled")
    } else if active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline) {
        Some("scan_deadline_exceeded")
    } else {
        None
    }
}
