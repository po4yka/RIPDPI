use std::io::{ErrorKind, Read, Write};

use crate::connectivity::adapters::http::{
    HttpObservation, classify_http_response, parse_http_response, read_http_headers,
    try_http_request_targets_with_key_log,
};
use crate::connectivity::adapters::tls::{
    TlsClientProfile, TlsKeyLogCallback, open_probe_stream_targets, open_probe_stream_targets_with_key_log,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::connectivity::adapters::util::{MAX_HTTP_BYTES, find_headers_end};
use crate::types::ThroughputTarget;

use super::target_parse::parse_http_target;
use super::types::ThroughputSample;

pub(super) fn measure_throughput_window(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ThroughputSample {
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
        Ok(parsed) => parsed,
        Err(err) => {
            return ThroughputSample { status: "invalid_target".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let started = std::time::Instant::now();
    let tls_name = if parsed.secure { Some(parsed.host.as_str()) } else { None };
    let stream_result = match key_log {
        Some(key_log) => open_probe_stream_targets_with_key_log(
            &parsed.connect_targets,
            parsed.port,
            transport,
            tls_name,
            parsed.secure,
            TlsClientProfile::Auto,
            None,
            Some(key_log),
        ),
        None => open_probe_stream_targets(
            &parsed.connect_targets,
            parsed.port,
            transport,
            tls_name,
            parsed.secure,
            TlsClientProfile::Auto,
            None,
        ),
    };
    let mut stream = match stream_result {
        Ok(result) => result.stream,
        Err(err) => {
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let request =
        format!("GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nConnection: close\r\n\r\n", parsed.path, parsed.host);
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return ThroughputSample {
            status: "http_unreachable".to_string(),
            bytes_read: 0,
            bps: 0,
            error: err.to_string(),
        };
    }
    let headers = match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
        Ok(headers) => headers,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let Some(header_end) = find_headers_end(&headers) else {
        stream.shutdown();
        return ThroughputSample {
            status: "http_unreachable".to_string(),
            bytes_read: 0,
            bps: 0,
            error: "response_missing_headers".to_string(),
        };
    };
    let response = match parse_http_response(&headers[..header_end], headers[header_end + 4..].to_vec()) {
        Ok(response) => response,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let status = classify_http_response(&response);
    let mut bytes_read = response.body.len().min(target.window_bytes);
    let mut last_error = "none".to_string();
    while bytes_read < target.window_bytes {
        let remaining = target.window_bytes - bytes_read;
        let mut chunk = vec![0u8; remaining.min(16 * 1024)];
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                bytes_read += read;
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                last_error = err.to_string();
                break;
            }
            Err(err) => {
                last_error = err.to_string();
                break;
            }
        }
    }
    stream.shutdown();
    let duration_ms = started.elapsed().as_millis().max(1) as u64;
    let bps = (bytes_read as u64).saturating_mul(8).saturating_mul(1000) / duration_ms;
    ThroughputSample { status, bytes_read, bps, error: last_error }
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
