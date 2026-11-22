use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::connectivity::endpoint::measure_throughput_with_options;
use crate::types::{ECH_TRANSFER_COMPARISON_LEGS, ProbeDetail, ProbeResult, ThroughputTarget, TransferMeasurement};

const LEGS: [(&str, TlsClientProfile); ECH_TRANSFER_COMPARISON_LEGS] = [
    ("tls13", TlsClientProfile::ComparisonTls13),
    ("echGrease", TlsClientProfile::ComparisonEchGrease),
    ("ech", TlsClientProfile::ComparisonEch),
];

pub(super) fn run(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ProbeResult {
    let mut details = Vec::with_capacity(LEGS.len() * 2);
    let mut complete = true;
    for (index, (name, profile)) in LEGS.into_iter().enumerate() {
        let (outcome, bytes) = if cancel.load(Ordering::Acquire)
            || active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline)
        {
            ("not_run", 0)
        } else {
            let options = ProbeStreamOptions {
                profile,
                application_protocol: ApplicationProtocolPolicy::Http11Only,
                certificate_policy: tls_verifier.into(),
                key_log,
            };
            let sample = measure_throughput_with_options(target, transport, &options, cancel, index + 1, progress);
            (
                leg_outcome(sample.verification_outcome.unwrap_or("verified_download_failed"), &sample.failure_stage),
                sample.bytes_read,
            )
        };
        complete &= outcome != "not_run";
        details.push(ProbeDetail { key: format!("{name}Outcome"), value: outcome.into() });
        details.push(ProbeDetail { key: format!("{name}Bytes"), value: bytes.to_string() });
    }
    ProbeResult {
        probe_type: "throughput_window".into(),
        target: target.label.clone(),
        outcome: if complete { "ech_transfer_comparison_recorded" } else { "ech_transfer_comparison_inconclusive" }
            .into(),
        details,
    }
}

fn leg_outcome(outcome: &str, stage: &str) -> &'static str {
    match outcome {
        "ech_config_missing" => "ech_config_missing",
        "ech_config_error" => "ech_config_error",
        "verified_download_complete" => "verified",
        "verified_download_inconclusive" => "not_run",
        _ if stage == "tls_handshake" => "tls_failed",
        _ if matches!(
            stage,
            "dns_resolution" | "tcp_connect" | "socks5_negotiation" | "stream_setup" | "target_parse"
        ) =>
        {
            "connection_failed"
        }
        "verified_download_timeout" if stage == "body_read" => "body_timeout",
        "verified_download_timeout" => "response_timeout",
        "verified_download_early_eof" => "early_eof",
        "verified_download_size_mismatch" => "size_mismatch",
        "verified_download_hash_mismatch" => "hash_mismatch",
        _ => "http_failed",
    }
}

#[cfg(test)]
mod tests;
