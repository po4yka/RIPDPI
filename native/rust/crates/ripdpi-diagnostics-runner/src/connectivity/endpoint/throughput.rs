use std::io::{self, Read, Write};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::{IO_TIMEOUT, active_scan_io_deadline, with_scan_io_deadline};
use ripdpi_diagnostics_transport::transport::ConnectionStream;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::http::{
    HttpObservation, classify_http_response, parse_http_response, try_http_request_targets_with_key_log,
};
use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamError, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{ThroughputTarget, TransferMeasurement};
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
    let mut tracker = TransferTracker::new(run_index, target.runs.clamp(1, 10), progress);
    let deadline = (tracker.started + Duration::from_secs(30))
        .min(active_scan_io_deadline().unwrap_or(tracker.started + Duration::from_secs(30)));
    let (mut sample, reason) = with_scan_io_deadline(Some(deadline), || {
        measure_run(target, transport, key_log, tls_verifier, cancel, deadline, &mut tracker)
    });
    tracker.finish(reason, target.window_bytes);
    sample.duration_ms = tracker.measurement.elapsed_ms.max(1);
    sample.bytes_read = tracker.measurement.received_body_byte_count as usize;
    sample.bps =
        tracker.measurement.received_body_byte_count.saturating_mul(8).saturating_mul(1000) / sample.duration_ms;
    sample.measurement = Some(tracker.measurement);
    sample
}

fn measure_run(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    deadline: Instant,
    tracker: &mut TransferTracker<'_>,
) -> (ThroughputSample, &'static str) {
    let started = tracker.started;
    if let Some(reason) = interruption(cancel, deadline) {
        return (failed_sample(started, "stream_setup", reason.to_string()), reason);
    }
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
        Ok(parsed) => parsed,
        Err(err) => return (failed_sample(started, "target_parse", err), "setup_error"),
    };
    let options = ProbeStreamOptions {
        verify_certificates: parsed.secure,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier,
        key_log,
    };
    let result = open_probe_stream_targets_with_options_and_abort(
        &parsed.connect_targets,
        parsed.port,
        transport,
        parsed.secure.then_some(parsed.host.as_str()),
        &options,
        &|| interruption(cancel, deadline),
    );
    let result = match result {
        Ok(result) => result,
        Err(error) => {
            return (failed_stream_sample(error, transport), interruption(cancel, deadline).unwrap_or("setup_error"));
        }
    };
    let address_family = result
        .connected_addr
        .map(|address| if address.is_ipv4() { "ipv4" } else { "ipv6" }.to_string())
        .or_else(|| matches!(transport, TransportConfig::Socks5 { .. }).then(|| "proxy_resolved_unknown".to_string()));
    let mut sample = failed_sample_with_connection(
        started,
        "request_write",
        "none".to_string(),
        result.negotiated_alpn,
        address_family,
        Some(result.tcp_connect_ms),
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
    let mut authority = if parsed.host.contains(':') { format!("[{}]", parsed.host) } else { parsed.host.clone() };
    if parsed.port != if parsed.secure { 443 } else { 80 } {
        authority.push_str(&format!(":{}", parsed.port));
    }
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
        parsed.path, authority
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
    let mut bounded_stream = BoundedTransferStream { stream, deadline };
    let mut reader = TransferReader::new(&mut bounded_stream, cancel, deadline, IO_TIMEOUT);
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
    if !(200..300).contains(&response.status_code) {
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
    let reached_window = tracker.measurement.received_body_byte_count >= window as u64
        && matches!(reason, "content_length_complete" | "chunked_complete" | "eof_complete" | "window_limit");
    sample.read_completion = if reached_window {
        "window_complete"
    } else {
        match reason {
            "idle_timeout" => "read_timeout",
            "content_length_complete" | "chunked_complete" | "eof_complete" | "early_eof" => "clean_eof_before_window",
            _ => "read_error",
        }
    }
    .to_string();
    sample.failure_stage = if reached_window { "none" } else { "body_read" }.to_string();
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
struct BoundedTransferStream<'a> {
    stream: &'a mut ConnectionStream,
    deadline: Instant,
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

fn response_headers<R: std::io::Read>(
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
            TransportConfig::Socks5 { .. } => Some("proxy_resolved_unknown".to_string()),
            TransportConfig::Direct { .. } => None,
        });
    ThroughputSample {
        status: "http_unreachable".to_string(),
        bytes_read: 0,
        bps: 0,
        error: error.message,
        failure_stage: error.stage.as_str().to_string(),
        failure_class: error.stage.as_str().to_string(),
        duration_ms: error.duration_ms,
        http_status_code: None,
        negotiated_alpn: None,
        address_family,
        tcp_connect_ms: error.tcp_connect_ms,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
        measurement: None,
    }
}

fn failed_sample(started: std::time::Instant, failure_stage: &str, error: String) -> ThroughputSample {
    let failure_class = classify_failure(failure_stage, "not_started").to_string();
    ThroughputSample {
        status: if failure_stage == "target_parse" { "invalid_target" } else { "http_unreachable" }.to_string(),
        bytes_read: 0,
        bps: 0,
        error,
        failure_stage: failure_stage.to_string(),
        failure_class,
        duration_ms: started.elapsed().as_millis().max(1) as u64,
        http_status_code: None,
        negotiated_alpn: None,
        address_family: None,
        tcp_connect_ms: None,
        tls_handshake_ms: None,
        read_completion: "not_started".to_string(),
        retry_attempt: 0,
        measurement: None,
    }
}

fn classify_failure(failure_stage: &str, read_completion: &str) -> &'static str {
    match failure_stage {
        "none" => "none",
        "target_parse" => "target_parse",
        "request_write" => "request_io",
        "response_headers" | "response_parse" => "http_response",
        "body_read" if read_completion == "read_timeout" => "read_timeout",
        "body_read" if read_completion == "clean_eof_before_window" => "early_eof",
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
    tcp_connect_ms: Option<u64>,
    tls_handshake_ms: Option<u64>,
) -> ThroughputSample {
    let mut sample = failed_sample(started, failure_stage, error);
    sample.negotiated_alpn = negotiated_alpn;
    sample.address_family = address_family;
    sample.tcp_connect_ms = tcp_connect_ms;
    sample.tls_handshake_ms = tls_handshake_ms;
    sample
}

pub(super) fn probe_http_url(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> HttpObservation {
    match parse_http_target(url, connect_ip, connect_ips, port_override) {
        Ok(parsed) => try_http_request_targets_with_key_log(
            &parsed.connect_targets,
            parsed.port,
            transport,
            &parsed.host,
            &parsed.path,
            parsed.secure,
            key_log,
        ),
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

#[cfg(test)]
mod tests;
