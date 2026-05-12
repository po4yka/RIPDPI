use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, ThroughputTarget};

use super::super::endpoint::measure_throughput_window;

pub fn run_throughput_probe(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    let samples =
        (0..target.runs.max(1)).map(|_| measure_throughput_window(target, transport, key_log)).collect::<Vec<_>>();
    let mut bps_values = samples.iter().map(|sample| sample.bps).filter(|bps| *bps > 0).collect::<Vec<_>>();
    bps_values.sort_unstable();
    let median_bps = if bps_values.is_empty() { 0 } else { bps_values[bps_values.len() / 2] };
    let outcome = if samples.iter().any(|sample| sample.status == "http_ok" && sample.bps > 0) {
        "throughput_measured"
    } else {
        "throughput_failed"
    };
    ProbeResult {
        probe_type: "throughput_window".to_string(),
        target: target.label.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "url".to_string(), value: target.url.clone() },
            ProbeDetail { key: "isControl".to_string(), value: target.is_control.to_string() },
            ProbeDetail { key: "windowBytes".to_string(), value: target.window_bytes.to_string() },
            ProbeDetail { key: "runs".to_string(), value: target.runs.to_string() },
            ProbeDetail {
                key: "bpsReadings".to_string(),
                value: samples.iter().map(|sample| sample.bps.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "statusReadings".to_string(),
                value: samples.iter().map(|sample| sample.status.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "byteReadings".to_string(),
                value: samples.iter().map(|sample| sample.bytes_read.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "errorReadings".to_string(),
                value: samples.iter().map(|sample| sample.error.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail { key: "medianBps".to_string(), value: median_bps.to_string() },
        ],
    }
}
