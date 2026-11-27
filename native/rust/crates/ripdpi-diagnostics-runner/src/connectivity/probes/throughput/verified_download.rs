use super::{
    Arc, AtomicBool, ProbeDetail, ProbeResult, ServerCertVerifier, ThroughputTarget, TlsKeyLogCallback,
    TransferEvidence, TransferMeasurement, TransportConfig, measure_throughput_run,
};

pub(super) fn run(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ProbeResult {
    let sample = measure_throughput_run(target, transport, key_log, verifier, cancel, 1, progress);
    let outcome = sample.verification_outcome.unwrap_or("verified_download_failed");
    let expected_bytes = target.verification.as_ref().map_or(0, |proof| proof.expected_body_bytes);
    let fields = [
        ("expectedBodyBytes", expected_bytes.to_string()),
        ("byteReadings", sample.bytes_read.to_string()),
        ("failureStageReadings", sample.failure_stage),
        ("readCompletionReadings", sample.read_completion),
        ("transportOpenMsReadings", super::reading(sample.transport_open_ms)),
        ("tlsHandshakeMsReadings", super::reading(sample.tls_handshake_ms)),
        ("httpStatusCodeReadings", super::reading(sample.http_status_code.map(u64::from))),
    ];
    let mut details: Vec<_> = fields.into_iter().map(|(key, value)| ProbeDetail { key: key.into(), value }).collect();
    if let Some(leg) = target.transfer_volume_leg() {
        details.push(ProbeDetail { key: "transferVolumeLeg".into(), value: leg.into() });
        if let Some(class) = &target.target_class {
            details.push(ProbeDetail { key: "targetClass".into(), value: class.clone() });
        }
        if let Some(asn) = target.destination_asn {
            details.push(ProbeDetail { key: "destinationAsn".into(), value: asn.to_string() });
        }
    }
    if let Some(measurement) = sample.measurement
        && let Ok(value) = (TransferEvidence { version: 1, runs: vec![measurement] }).to_json()
    {
        details.push(ProbeDetail { key: "transferEvidence".into(), value });
    }
    ProbeResult {
        probe_type: "throughput_window".into(),
        target: target.label.clone(),
        outcome: outcome.into(),
        details,
    }
}
