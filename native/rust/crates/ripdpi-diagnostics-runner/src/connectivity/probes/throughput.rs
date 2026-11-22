use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, ThroughputTarget};

use super::super::endpoint::{measure_throughput_run, run_verified_upload};
use crate::types::{TransferEvidence, TransferMeasurement};
use rustls::client::danger::ServerCertVerifier;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

mod ech_comparison;
#[cfg(test)]
mod https_fixture;
mod verified_download;
#[cfg(test)]
mod verified_download_tests;
#[cfg(test)]
mod verified_upload_tests;

pub fn run_throughput_probe(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    run_throughput_probe_with_progress(target, transport, key_log, None, &AtomicBool::new(false), &mut |_| {})
}

pub fn run_throughput_probe_with_progress(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ProbeResult {
    if target.upload_body_bytes.is_some() {
        return run_verified_upload(target, transport, key_log, tls_verifier, cancel);
    }
    if target.verification.as_ref().is_some_and(|proof| proof.ech_comparison_ip.is_some()) {
        return ech_comparison::run(target, transport, key_log, tls_verifier, cancel, progress);
    }
    if target.verification.is_some() {
        return verified_download::run(target, transport, key_log, tls_verifier, cancel, progress);
    }
    let planned_runs = target.runs.clamp(1, 10);
    let mut samples = Vec::new();
    let mut stop_reason = "none";
    for run_index in 1..=planned_runs {
        if cancel.load(Ordering::Acquire) {
            stop_reason = "cancelled";
            break;
        }
        if ripdpi_diagnostics_contracts::util::active_scan_io_deadline()
            .is_some_and(|deadline| std::time::Instant::now() >= deadline)
        {
            stop_reason = "deadline";
            break;
        }
        samples.push(measure_throughput_run(target, transport, key_log, tls_verifier, cancel, run_index, progress));
    }
    let windows = samples.iter().map(|sample| sample.accepted_window().then_some(sample)).collect::<Vec<_>>();
    let mut bps_values = windows.iter().flatten().map(|sample| sample.bps).collect::<Vec<_>>();
    let mut body_rates = windows.iter().flatten().filter_map(|sample| sample.body_goodput_bps).collect::<Vec<_>>();
    bps_values.sort_unstable();
    body_rates.sort_unstable();
    let accepted_runs = bps_values.len();
    let interrupted_or_short = samples.iter().any(|sample| {
        (sample.accepted_response() && sample.read_completion == "body_complete_before_window")
            || sample
                .measurement
                .as_ref()
                .and_then(|run| run.termination_reason.as_deref())
                .is_some_and(|reason| matches!(reason, "deadline" | "cancelled"))
    });
    let outcome = if accepted_runs == planned_runs {
        "throughput_measured"
    } else if accepted_runs > 0 || samples.len() < planned_runs || interrupted_or_short {
        "throughput_inconclusive"
    } else {
        "throughput_failed"
    };
    let body_median = if body_rates.len() == planned_runs { median(&body_rates).to_string() } else { "none".into() };
    let mut result = ProbeResult {
        probe_type: "throughput_window".to_string(),
        target: target.label.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "url".to_string(), value: target.url.clone() },
            ProbeDetail { key: "isControl".to_string(), value: target.is_control.to_string() },
            ProbeDetail { key: "windowBytes".to_string(), value: target.window_bytes.to_string() },
            ProbeDetail { key: "runs".to_string(), value: planned_runs.to_string() },
            ProbeDetail {
                key: "bpsReadings".to_string(),
                value: windows
                    .iter()
                    .map(|sample| reading(sample.map(|sample| sample.bps)))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail {
                key: "bodyGoodputBpsReadings".into(),
                value: windows
                    .iter()
                    .map(|sample| reading(sample.and_then(|sample| sample.body_goodput_bps)))
                    .collect::<Vec<_>>()
                    .join("|"),
            },
            ProbeDetail { key: "attemptedRuns".into(), value: samples.len().to_string() },
            ProbeDetail { key: "acceptedRuns".into(), value: accepted_runs.to_string() },
            ProbeDetail { key: "remainingRunStopReason".into(), value: stop_reason.into() },
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
                key: "transportOpenMsReadings".to_string(),
                value: samples
                    .iter()
                    .map(|sample| sample.transport_open_ms.map_or_else(|| "none".to_string(), |ms| ms.to_string()))
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
            ProbeDetail { key: "medianBps".into(), value: median(&bps_values).to_string() },
            ProbeDetail { key: "medianBodyGoodputBps".into(), value: body_median },
        ],
    };
    let evidence =
        TransferEvidence { version: 1, runs: samples.iter().filter_map(|sample| sample.measurement.clone()).collect() };
    if let Ok(value) = evidence.to_json() {
        result.details.push(ProbeDetail { key: "transferEvidence".to_string(), value });
    }
    result
}

fn reading(value: Option<u64>) -> String {
    value.map_or_else(|| "none".to_string(), |bps| bps.to_string())
}

/// Median of sorted readings; the mean of the two middle values for an even
/// count (the default is two runs), 0 when empty.
fn median(sorted: &[u64]) -> u64 {
    match sorted.len() {
        0 => 0,
        len if len % 2 == 1 => sorted[len / 2],
        // Infallible: the mean of two u64 values fits in u64.
        len => u64::try_from((u128::from(sorted[len / 2 - 1]) + u128::from(sorted[len / 2])) / 2).unwrap_or(u64::MAX),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn median_of_two_runs_is_their_mean() {
        assert_eq!(median(&[1_000_000, 3_000_000]), 2_000_000);
        assert_eq!(median(&[1, 2, 9]), 2);
        assert_eq!(median(&[]), 0);
        assert_eq!(median(&[u64::MAX, u64::MAX]), u64::MAX);
    }

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
            upload_body_bytes: None,
            verification: None,
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
        assert_eq!(detail_value(&result, "transportOpenMsReadings"), "none");
        assert_eq!(detail_value(&result, "tlsHandshakeMsReadings"), "none");
        assert_eq!(detail_value(&result, "readCompletionReadings"), "not_started");
        assert_eq!(detail_value(&result, "retryAttemptReadings"), "0");
        assert!(detail_value(&result, "durationMsReadings").parse::<u64>().expect("duration parses") >= 1);
    }

    #[test]
    fn evidence_throughput_distinguishes_complete_short_truncated_and_full_bodies() {
        use crate::test_fixtures::HttpTextServer;
        for (response, expected) in [
            ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", "throughput_inconclusive"),
            ("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nok", "throughput_failed"),
            ("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nfull", "throughput_measured"),
            ("HTTP/1.1 203 Non-Authoritative Information\r\nContent-Length: 4\r\n\r\nfull", "throughput_measured"),
            ("HTTP/1.1 204 No Content\r\n\r\n", "throughput_inconclusive"),
            (
                "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-3/8\r\nContent-Length: 4\r\n\r\nfull",
                "throughput_failed",
            ),
        ] {
            let server = HttpTextServer::start(move |_| response.as_bytes().to_vec());
            let target = local_target(server.port(), 4, 1);
            let result = run_throughput_probe(&target, &TransportConfig::Direct { route_experiment: None }, None);
            assert_eq!(result.outcome, expected);
            if expected != "throughput_measured" {
                assert_eq!(detail_value(&result, "medianBps"), "0");
            }
        }
    }
    #[test]
    fn blockpage_window_does_not_enter_full_request_or_body_goodput_aggregate() {
        use crate::test_fixtures::HttpTextServer;
        use std::sync::atomic::AtomicUsize;

        let attempts = AtomicUsize::new(0);
        let server = HttpTextServer::start(move |_| {
            let body = if attempts.fetch_add(1, Ordering::Relaxed) == 0 {
                "This is a normal payload"
            } else {
                "blocked by your provider"
            };
            format!("HTTP/1.1 203 Non-Authoritative Information\r\nContent-Length: 24\r\n\r\n{body}").into_bytes()
        });
        let target = local_target(server.port(), 24, 2);
        let result = run_throughput_probe(&target, &TransportConfig::Direct { route_experiment: None }, None);
        let (eligible_reading, rejected_reading) = detail_value(&result, "bpsReadings").split_once('|').unwrap();

        assert_eq!(result.outcome, "throughput_inconclusive");
        assert_eq!(detail_value(&result, "attemptedRuns"), "2");
        assert_eq!(detail_value(&result, "acceptedRuns"), "1");
        assert_eq!(detail_value(&result, "statusReadings"), "http_status_203|http_blockpage");
        assert_eq!(rejected_reading, "none");
        assert_eq!(detail_value(&result, "medianBps"), eligible_reading);
        assert_eq!(detail_value(&result, "medianBodyGoodputBps"), "none");
    }

    #[test]
    fn cancellation_between_runs_preserves_one_accepted_window_and_missing_coverage() {
        use crate::test_fixtures::HttpTextServer;

        let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "full");
        let target = local_target(server.port(), 4, 2);
        let cancel = AtomicBool::new(false);
        let result = run_throughput_probe_with_progress(
            &target,
            &TransportConfig::Direct { route_experiment: None },
            None,
            None,
            &cancel,
            &mut |run| {
                if run.termination_reason.is_some() {
                    cancel.store(true, Ordering::Release);
                }
            },
        );

        assert_eq!(result.outcome, "throughput_inconclusive");
        assert_eq!(detail_value(&result, "runs"), "2");
        assert_eq!(detail_value(&result, "attemptedRuns"), "1");
        assert_eq!(detail_value(&result, "acceptedRuns"), "1");
        assert_eq!(detail_value(&result, "remainingRunStopReason"), "cancelled");
        assert_ne!(detail_value(&result, "medianBps"), "0");
    }

    fn local_target(port: u16, window_bytes: usize, runs: usize) -> ThroughputTarget {
        ThroughputTarget {
            upload_body_bytes: None,
            verification: None,
            id: "body-completion".into(),
            label: "Body completion".into(),
            url: format!("http://127.0.0.1:{port}/"),
            connect_ip: None,
            connect_ips: vec![],
            port: None,
            is_control: false,
            window_bytes,
            runs,
        }
    }
}
