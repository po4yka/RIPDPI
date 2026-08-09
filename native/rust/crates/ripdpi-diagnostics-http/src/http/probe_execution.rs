use std::io::Write;

use crate::tls::{
    TlsClientProfile, TlsKeyLogCallback, open_probe_stream, open_probe_stream_targets,
    open_probe_stream_targets_with_key_log, open_probe_stream_with_key_log,
};
use crate::transport::{TargetAddress, TransportConfig};
use crate::util::MAX_HTTP_BYTES;

use super::classifier::classify_http_response;
use super::response_parser::read_http_response_with_ttfb;
use super::types::{HttpObservation, HttpResponse};

struct TimedHttpResponse {
    response: HttpResponse,
    ttfb_ms: u64,
}

pub fn try_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> HttpObservation {
    try_http_request_with_key_log(target, port, transport, host_header, path, secure, None)
}

pub fn try_http_request_with_key_log(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> HttpObservation {
    match execute_http_request_with_key_log_timed(target, port, transport, host_header, path, secure, key_log) {
        Ok(timed) => HttpObservation {
            status: classify_http_response(&timed.response),
            response: Some(timed.response),
            error: None,
            ttfb_ms: Some(timed.ttfb_ms),
        },
        Err(err) => {
            HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err), ttfb_ms: None }
        }
    }
}

pub fn try_http_request_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> HttpObservation {
    try_http_request_targets_with_key_log(targets, port, transport, host_header, path, secure, None)
}

pub fn try_http_request_targets_with_key_log(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> HttpObservation {
    match execute_http_request_targets_with_key_log_timed(targets, port, transport, host_header, path, secure, key_log)
    {
        Ok(timed) => HttpObservation {
            status: classify_http_response(&timed.response),
            response: Some(timed.response),
            error: None,
            ttfb_ms: Some(timed.ttfb_ms),
        },
        Err(err) => {
            HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err), ttfb_ms: None }
        }
    }
}

pub fn execute_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> Result<HttpResponse, String> {
    execute_http_request_with_key_log_timed(target, port, transport, host_header, path, secure, None)
        .map(|timed| timed.response)
}

pub fn execute_http_request_with_key_log(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<HttpResponse, String> {
    execute_http_request_with_key_log_timed(target, port, transport, host_header, path, secure, key_log)
        .map(|timed| timed.response)
}

fn execute_http_request_with_key_log_timed(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<TimedHttpResponse, String> {
    let tls_name = if secure { Some(host_header) } else { None };
    let mut stream = match key_log {
        Some(key_log) => {
            open_probe_stream_with_key_log(
                target,
                port,
                transport,
                tls_name,
                secure,
                TlsClientProfile::Auto,
                None,
                Some(key_log),
            )?
            .stream
        }
        None => open_probe_stream(target, port, transport, tls_name, secure, TlsClientProfile::Auto, None)?.stream,
    };
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let (response, ttfb_ms) = read_http_response_with_ttfb(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(TimedHttpResponse { response, ttfb_ms })
}

pub fn execute_http_request_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> Result<HttpResponse, String> {
    execute_http_request_targets_with_key_log_timed(targets, port, transport, host_header, path, secure, None)
        .map(|timed| timed.response)
}

pub fn execute_http_request_targets_with_key_log(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<HttpResponse, String> {
    execute_http_request_targets_with_key_log_timed(targets, port, transport, host_header, path, secure, key_log)
        .map(|timed| timed.response)
}

fn execute_http_request_targets_with_key_log_timed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
) -> Result<TimedHttpResponse, String> {
    let tls_name = if secure { Some(host_header) } else { None };
    let mut stream = match key_log {
        Some(key_log) => {
            open_probe_stream_targets_with_key_log(
                targets,
                port,
                transport,
                tls_name,
                secure,
                TlsClientProfile::Auto,
                None,
                Some(key_log),
            )?
            .stream
        }
        None => {
            open_probe_stream_targets(targets, port, transport, tls_name, secure, TlsClientProfile::Auto, None)?.stream
        }
    };
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let (response, ttfb_ms) = read_http_response_with_ttfb(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(TimedHttpResponse { response, ttfb_ms })
}
