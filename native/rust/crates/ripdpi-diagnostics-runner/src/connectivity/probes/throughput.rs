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
    let mut bps_values = samples
        .iter()
        .filter(|sample| sample.completed_window())
        .map(|sample| sample.bps)
        .filter(|bps| *bps > 0)
        .collect::<Vec<_>>();
    bps_values.sort_unstable();
    let median_bps = if bps_values.is_empty() { 0 } else { bps_values[bps_values.len() / 2] };
    let outcome =
        if samples.iter().any(|sample| sample.status == "http_ok" && sample.bps > 0 && sample.completed_window()) {
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
            ProbeDetail {
                key: "failureStageReadings".to_string(),
                value: samples.iter().map(|sample| sample.failure_stage.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "failureClassReadings".to_string(),
                value: samples.iter().map(|sample| sample.failure_class.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "durationMsReadings".to_string(),
                value: samples.iter().map(|sample| sample.duration_ms.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "httpStatusCodeReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.http_status_code.map_or_else(|| "none".to_string(), |code| code.to_string()))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "negotiatedAlpnReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.negotiated_alpn.clone().unwrap_or_else(|| "none".to_string()))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "addressFamilyReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.address_family.clone().unwrap_or_else(|| "none".to_string()))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "tcpConnectMsReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.tcp_connect_ms.map_or_else(|| "none".to_string(), |ms| ms.to_string()))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "tlsHandshakeMsReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.tls_handshake_ms.map_or_else(|| "none".to_string(), |ms| ms.to_string()))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "readCompletionReadings".to_string(),
                value: samples.iter().map(|sample| sample.read_completion.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "retryAttemptReadings".to_string(),
                value: samples.iter().map(|sample| sample.retry_attempt.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail { key: "medianBps".to_string(), value: median_bps.to_string() },
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn detail_value<'a>(result: &'a ProbeResult, key: &str) -> &'a str {
        result
            .details
            .iter()
            .find(|detail| detail.key == key)
            .map(|detail| detail.value.as_str())
            .expect("detail exists")
    }

    #[test]
    fn throughput_failure_details_include_stage_and_protocol_context() {
        let target = ThroughputTarget {
            id: "bad-url".to_string(),
            label: "Bad URL".to_string(),
            url: "not-a-url".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            port: None,
            is_control: false,
            window_bytes: 1024,
            runs: 1,
        };

        let result = run_throughput_probe(&target, &TransportConfig::Direct { route_experiment: None }, None);

        assert_eq!(result.outcome, "throughput_failed");
        assert_eq!(detail_value(&result, "failureStageReadings"), "target_parse");
        assert_eq!(detail_value(&result, "failureClassReadings"), "target_parse");
        assert_eq!(detail_value(&result, "httpStatusCodeReadings"), "none");
        assert_eq!(detail_value(&result, "negotiatedAlpnReadings"), "none");
        assert_eq!(detail_value(&result, "addressFamilyReadings"), "none");
        assert_eq!(detail_value(&result, "tcpConnectMsReadings"), "none");
        assert_eq!(detail_value(&result, "tlsHandshakeMsReadings"), "none");
        assert_eq!(detail_value(&result, "readCompletionReadings"), "not_started");
        assert_eq!(detail_value(&result, "retryAttemptReadings"), "0");
        assert!(detail_value(&result, "durationMsReadings").parse::<u64>().expect("duration parses") >= 1);
    }
}
