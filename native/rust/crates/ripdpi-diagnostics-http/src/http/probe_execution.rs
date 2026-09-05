use std::io::Write;

use crate::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options,
};
use crate::transport::{TargetAddress, TransportConfig};
use crate::util::MAX_HTTP_BYTES;

use super::classifier::classify_http_response;
use super::response_parser::read_http_response;
use super::types::{HttpObservation, HttpResponse};

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
    match execute_http_request_with_key_log(target, port, transport, host_header, path, secure, key_log) {
        Ok(response) => {
            HttpObservation { status: classify_http_response(&response), response: Some(response), error: None }
        }
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
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
    match execute_http_request_targets_with_key_log(targets, port, transport, host_header, path, secure, key_log) {
        Ok(response) => {
            HttpObservation { status: classify_http_response(&response), response: Some(response), error: None }
        }
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
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
    execute_http_request_with_key_log(target, port, transport, host_header, path, secure, None)
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
    let tls_name = if secure { Some(host_header) } else { None };
    let options = ProbeStreamOptions {
        verify_certificates: secure,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: None,
        key_log,
    };
    let mut stream =
        open_probe_stream_targets_with_options(std::slice::from_ref(target), port, transport, tls_name, &options)
            .map_err(|err| err.to_string())?
            .stream;
    let request = request_head(host_header, port, path, secure);
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let response = read_http_response(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(response)
}

pub fn execute_http_request_targets(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> Result<HttpResponse, String> {
    execute_http_request_targets_with_key_log(targets, port, transport, host_header, path, secure, None)
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
    let tls_name = if secure { Some(host_header) } else { None };
    let options = ProbeStreamOptions {
        verify_certificates: secure,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: None,
        key_log,
    };
    let mut stream = open_probe_stream_targets_with_options(targets, port, transport, tls_name, &options)
        .map_err(|err| err.to_string())?
        .stream;
    let request = request_head(host_header, port, path, secure);
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let response = read_http_response(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(response)
}

fn request_head(host: &str, port: u16, path: &str, secure: bool) -> String {
    let mut authority = if host.parse::<std::net::Ipv6Addr>().is_ok() { format!("[{host}]") } else { host.to_string() };
    if port != if secure { 443 } else { 80 } {
        authority.push_str(&format!(":{port}"));
    }
    format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nAccept: */*\r\nConnection: close\r\n\r\n")
}
