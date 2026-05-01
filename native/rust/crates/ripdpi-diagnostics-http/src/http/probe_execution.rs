use std::io::Write;

use crate::tls::{open_probe_stream, open_probe_stream_targets, TlsClientProfile};
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
    match execute_http_request(target, port, transport, host_header, path, secure) {
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
    match execute_http_request_targets(targets, port, transport, host_header, path, secure) {
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
    let mut stream = open_probe_stream(
        target,
        port,
        transport,
        if secure { Some(host_header) } else { None },
        secure,
        TlsClientProfile::Auto,
        None,
    )?
    .stream;
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
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
    let mut stream = open_probe_stream_targets(
        targets,
        port,
        transport,
        if secure { Some(host_header) } else { None },
        secure,
        TlsClientProfile::Auto,
        None,
    )?
    .stream;
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let response = read_http_response(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(response)
}
