use std::io::{self, Read, Write};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::{IO_TIMEOUT, active_scan_io_deadline, with_scan_io_deadline};
use ripdpi_diagnostics_transport::transport::{ConnectionStream, TargetAddress};
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{classify_http_response, parse_http_response};
use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamError, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{
    ECH_TRANSFER_COMPARISON_LEGS, HTTP3_TRANSFER_COMPARISON_LEGS, HTTP3_TRANSFER_COMPARISON_PROFILE_ID,
    TRANSFER_VOLUME_SHORT_ID, ThroughputTarget, TransferMeasurement,
};
use ripdpi_diagnostics_tls::tls::open_probe_stream_targets_with_options_and_abort;

use super::target_parse::parse_http_target;
use super::transfer::{TransferReader, TransferTracker, interruption};
use super::types::ThroughputSample;

#[cfg(test)]
pub(super) fn measure_throughput_window(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ThroughputSample {
    measure_throughput_window_with_verifier(target, transport, key_log, None)
}

#[cfg(test)]
fn measure_throughput_window_with_verifier(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ThroughputSample {
    measure_throughput_run(target, transport, key_log, tls_verifier, &AtomicBool::new(false), 1, &mut |_| {})
}

pub(in crate::connectivity) fn measure_throughput_run(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    run_index: usize,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ThroughputSample {
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: tls_verifier.into(),
        key_log,
    };
    measure_throughput_with_options(target, transport, &options, cancel, run_index, progress)
}

pub(in crate::connectivity) fn measure_throughput_with_options(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    options: &ProbeStreamOptions<'_>,
    cancel: &AtomicBool,
    run_index: usize,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ThroughputSample {
    let is_ech_comparison = target.verification.as_ref().is_some_and(|proof| proof.ech_comparison_ip.is_some());
    let runs = if target.id == HTTP3_TRANSFER_COMPARISON_PROFILE_ID {
        HTTP3_TRANSFER_COMPARISON_LEGS
    } else if is_ech_comparison {
        ECH_TRANSFER_COMPARISON_LEGS
    } else {
        target.runs.clamp(1, 10)
    };
    let mut tracker = TransferTracker::new(run_index, runs, progress);
    if target.verification.is_some() {
        tracker.verify_body();
    }
    let mut deadline = transfer_deadline(tracker.started);
    if is_ech_comparison {
        // One slow leg must leave time for the other two.
        deadline = deadline.min(tracker.started + Duration::from_secs(8));
    } else if target.id == TRANSFER_VOLUME_SHORT_ID {
        // Preserve the rest of the shared deadline for the long response.
        deadline = tracker.started + deadline.saturating_duration_since(tracker.started) / 3;
    }
    let (mut sample, reason) = with_scan_io_deadline(Some(deadline), || {
        measure_run(target, transport, options, cancel, deadline, &mut tracker)
    });
    tracker.finish(reason, target.window_bytes);
    sample.verification_outcome = target.verification.as_ref().map(|expected| match reason {
        "idle_timeout" | "deadline" => "verified_download_timeout",
        "early_eof" => "verified_download_early_eof",
        "cancelled" => "verified_download_inconclusive",
        "ech_config_missing" | "ech_dns_failed" | "ech_config_invalid" | "ech_rejected" => reason,
        "window_limit" => "verified_download_size_mismatch",
        "eof_complete" if tracker.measurement.received_body_byte_count < expected.expected_body_bytes as u64 => {
            "verified_download_early_eof"
        }
        _ if !tracker.measurement.response_complete => "verified_download_failed",
        _ if tracker.measurement.received_body_byte_count != expected.expected_body_bytes as u64 => {
            "verified_download_size_mismatch"
        }
        _ if tracker.matches_sha256(&expected.sha256) => "verified_download_complete",
        _ => "verified_download_hash_mismatch",
    });
    sample.duration_ms = tracker.measurement.elapsed_ms.max(1);
    sample.bytes_read = tracker.measurement.received_body_byte_count as usize;
    sample.bps =
        tracker.measurement.received_body_byte_count.saturating_mul(8).saturating_mul(1000) / sample.duration_ms;
    sample.body_goodput_bps = tracker.body_goodput_bps();
    sample.measurement = Some(tracker.measurement);
    sample
}

pub(super) fn transfer_deadline(started: Instant) -> Instant {
    let limit = started + Duration::from_secs(30);
    active_scan_io_deadline().map_or(limit, |deadline| limit.min(deadline))
}

fn measure_run(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    options: &ProbeStreamOptions<'_>,
    cancel: &AtomicBool,
    deadline: Instant,
    tracker: &mut TransferTracker<'_>,
) -> (ThroughputSample, &'static str) {
    let started = tracker.started;
    if let Some(reason) = interruption(cancel, deadline) {
        return (failed_sample(started, "stream_setup", reason.to_string()), reason);
    }
    if let Err(error) = target.validate_download() {
        return (failed_sample(started, "target_parse", error.to_string()), "setup_error");
    }
    let mut parsed =
        match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
            Ok(parsed) => parsed,
            Err(err) => return (failed_sample(started, "target_parse", err), "setup_error"),
        };
    if target.transfer_volume_leg().is_some() {
        let Some(ip) = target.pinned_download_ip() else {
            return (failed_sample(started, "target_parse", "missing fixed IP".into()), "setup_error");
        };
        if !matches!(transport, TransportConfig::Direct { route_experiment: None }) || target.verification.is_none() {
            return (
                failed_sample(started, "target_parse", "volume comparison requires verified RAW".into()),
                "setup_error",
            );
        }
        parsed.connect_targets = vec![TargetAddress::Ip(ip)];
    } else if let Some(ip) = target.verification.as_ref().and_then(|proof| proof.ech_comparison_ip) {
        parsed.connect_targets = vec![TargetAddress::Ip(ip)];
    }
    let result = open_probe_stream_targets_with_options_and_abort(
        &parsed.connect_targets,
        parsed.port,
        transport,
        parsed.secure.then_some(parsed.host.as_str()),
        options,
        &|| interruption(cancel, deadline),
    );
    let result = match result {
        Ok(result) => result,
        Err(error) => {
            let reason = interruption(cancel, deadline)
                .or_else(|| error.comparison_ech_failure_outcome())
                .unwrap_or("setup_error");
            return (failed_stream_sample(error, transport), reason);
        }
    };
    let address_family = result
        .connected_addr
        .map(|address| if address.is_ipv4() { "ipv4" } else { "ipv6" }.to_string())
        .or_else(|| {
            matches!(transport, TransportConfig::Socks5 { .. } | TransportConfig::Socks5Unix { .. })
                .then(|| "proxy_resolved_unknown".to_string())
        });
    let mut sample = failed_sample_with_connection(
        started,
        "request_write",
        "none".to_string(),
        result.negotiated_alpn,
        address_family,
        Some(result.transport_open_ms),
        parsed.secure.then_some(result.tls_handshake_ms),
    );
    let mut stream = result.stream;
    let socket = match &stream {
        ConnectionStream::Plain(socket) => socket,
        ConnectionStream::Tls(stream) => &stream.sock,
    };
    let remaining = deadline
        .saturating_duration_since(Instant::now())
        .min(Duration::from_millis(100))
        .max(Duration::from_millis(1));
    if let Err(error) =
        socket.set_read_timeout(Some(remaining)).and_then(|()| socket.set_write_timeout(Some(remaining)))
    {
        stream.shutdown();
        sample.error = error.to_string();
        return (sample, "setup_error");
    }
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
        parsed.path,
        parsed.authority()
    );
    if let Some(reason) = interruption(cancel, deadline) {
        stream.shutdown();
        return (sample, reason);
    }
    if let Err(error) = stream.write_all(request.as_bytes()).and_then(|()| stream.flush()) {
        stream.shutdown();
        sample.error = error.to_string();
        return (sample, interruption(cancel, deadline).unwrap_or("setup_error"));
    }
    let reason = read_response(&mut stream, target.window_bytes, cancel, deadline, tracker, &mut sample);
    stream.shutdown();
    (sample, reason)
}

fn read_response(
    stream: &mut ConnectionStream,
    window: usize,
    cancel: &AtomicBool,
    deadline: Instant,
    tracker: &mut TransferTracker<'_>,
    sample: &mut ThroughputSample,
) -> &'static str {
    let mut bounded_stream = BoundedTransferStream::new(stream, deadline);
    let mut reader = TransferReader::new(&mut bounded_stream, cancel, deadline, IO_TIMEOUT);
    if tracker.verifies_body() {
        reader.limit_read_ahead(window);
    }
    sample.failure_stage = "response_headers".to_string();
    let (headers, mut response) = match response_headers(&mut reader, tracker) {
        Ok(response) => response,
        Err(reason) => {
            sample.error = reason.to_string();
            sample.failure_class = "http_response".to_string();
            return reason;
        }
    };
    sample.status = classify_http_response(&response);
    sample.http_status_code = Some(response.status_code);
    let accepts_response = if tracker.verifies_body() {
        response.status_code == 200
            && !response.headers.contains_key("content-range")
            && response.headers.get("content-encoding").is_none_or(|coding| coding.eq_ignore_ascii_case("identity"))
    } else {
        sample.accepted_response()
    };
    if !accepts_response {
        sample.failure_stage = "response_status".to_string();
        sample.failure_class = "http_status".to_string();
        sample.error = format!("http_status_{}", response.status_code);
        return "http_error";
    }
    let reason = if matches!(response.status_code, 204 | 205) {
        tracker.measurement.expected_body_byte_count = Some(0);
        "content_length_complete"
    } else {
        reader.body(tracker, &headers, window).unwrap_or_else(|reason| reason)
    };
    response.body = std::mem::take(&mut tracker.body_prefix);
    sample.status = classify_http_response(&response);
    let body_complete = matches!(reason, "content_length_complete" | "chunked_complete" | "eof_complete");
    let reached_window =
        tracker.measurement.received_body_byte_count >= window as u64 && (body_complete || reason == "window_limit");
    sample.read_completion = if reached_window {
        "window_complete"
    } else if body_complete {
        "body_complete_before_window"
    } else {
        match reason {
            "idle_timeout" => "read_timeout",
            "early_eof" => "truncated_body",
            _ => "read_error",
        }
    }
    .to_string();
    sample.failure_stage = if reached_window || body_complete { "none" } else { "body_read" }.to_string();
    sample.failure_class = classify_failure(&sample.failure_stage, &sample.read_completion).to_string();
    sample.error =
        if matches!(reason, "content_length_complete" | "chunked_complete" | "eof_complete" | "window_limit") {
            "none"
        } else {
            reason
        }
        .to_string();
    reason
}

/// Recheck the deadline between TLS records, including records with no application data.
pub(super) struct BoundedTransferStream<'a> {
    stream: &'a mut ConnectionStream,
    deadline: Instant,
}

impl<'a> BoundedTransferStream<'a> {
    pub(super) fn new(stream: &'a mut ConnectionStream, deadline: Instant) -> Self {
        Self { stream, deadline }
    }
}

impl Read for BoundedTransferStream<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let timeout = self
            .deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .ok_or_else(|| io::Error::from(io::ErrorKind::TimedOut))?
            .min(Duration::from_millis(100));
        match self.stream {
            ConnectionStream::Plain(socket) => {
                socket.set_read_timeout(Some(timeout))?;
                socket.read(buffer)
            }
            ConnectionStream::Tls(stream) => {
                stream.sock.set_read_timeout(Some(timeout))?;
                stream.sock.set_write_timeout(Some(timeout))?;
                match stream.conn.reader().read(buffer) {
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                    result => return result,
                }
                // StreamOwned::read loops until plaintext arrives. Use one I/O round so
                // cancellation and absolute limits are checked even for TLS control traffic.
                stream.conn.complete_io(&mut stream.sock)?;
                stream.conn.reader().read(buffer)
            }
        }
    }
}

pub(super) fn response_headers<R: std::io::Read>(
    reader: &mut TransferReader<'_, R>,
    tracker: &mut TransferTracker<'_>,
) -> Result<(Vec<u8>, ripdpi_diagnostics_http::http::HttpResponse), &'static str> {
    for _ in 0..5 {
        let headers = reader.headers(tracker)?;
        let response = parse_http_response(&headers, Vec::new()).map_err(|_| "invalid_framing")?;
        if !(100..200).contains(&response.status_code) {
            return Ok((headers, response));
        }
        if response.status_code == 101 {
            return Err("invalid_framing");
        }
    }
    Err("invalid_framing")
}
fn failed_stream_sample(error: ProbeStreamError, transport: &TransportConfig) -> ThroughputSample {
    let address_family = error
        .connected_addr
        .map(|address| if address.is_ipv4() { "ipv4" } else { "ipv6" }.to_string())
        .or_else(|| match transport {
            TransportConfig::Socks5 { .. } | TransportConfig::Socks5Unix { .. } => {
                Some("proxy_resolved_unknown".to_string())
            }
            TransportConfig::Direct { .. } => None,
        });
    ThroughputSample {
        status: "http_unreachable".to_string(),
        bytes_read: 0,
        bps: 0,
        body_goodput_bps: None,
        error: error.message,
        failure_stage: error.stage.as_str().to_string(),
        failure_class: error.stage.as_str().to_string(),
        duration_ms: error.duration_ms,
        http_status_code: None,
        negotiated_alpn: None,
        address_family,
        transport_open_ms: error.transport_open_ms,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
        measurement: None,
        verification_outcome: None,
    }
}

fn failed_sample(started: std::time::Instant, failure_stage: &str, error: String) -> ThroughputSample {
    let failure_class = classify_failure(failure_stage, "not_started").to_string();
    ThroughputSample {
        status: if failure_stage == "target_parse" { "invalid_target" } else { "http_unreachable" }.to_string(),
        bytes_read: 0,
        bps: 0,
        body_goodput_bps: None,
        error,
        failure_stage: failure_stage.to_string(),
        failure_class,
        duration_ms: started.elapsed().as_millis().max(1) as u64,
        http_status_code: None,
        negotiated_alpn: None,
        address_family: None,
        transport_open_ms: None,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
        measurement: None,
        verification_outcome: None,
    }
}

fn classify_failure(failure_stage: &str, read_completion: &str) -> &'static str {
    match failure_stage {
        "none" => "none",
        "target_parse" => "target_parse",
        "request_write" => "request_io",
        "response_headers" | "response_parse" => "http_response",
        "body_read" if read_completion == "read_timeout" => "read_timeout",
        "body_read" if read_completion == "truncated_body" => "early_eof",
        "body_read" => "body_read",
        _ => "unknown",
    }
}

#[allow(clippy::too_many_arguments)]
fn failed_sample_with_connection(
    started: std::time::Instant,
    failure_stage: &str,
    error: String,
    negotiated_alpn: Option<String>,
    address_family: Option<String>,
    transport_open_ms: Option<u64>,
    tls_handshake_ms: Option<u64>,
) -> ThroughputSample {
    let mut sample = failed_sample(started, failure_stage, error);
    sample.negotiated_alpn = negotiated_alpn;
    sample.address_family = address_family;
    sample.transport_open_ms = transport_open_ms;
    sample.tls_handshake_ms = tls_handshake_ms;
    sample
}

#[cfg(test)]
mod tests;
