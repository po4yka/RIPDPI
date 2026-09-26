use std::io::Write;
use std::net::Shutdown;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;

use crate::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options, open_probe_stream_targets_with_options_and_abort,
};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig};
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
    let request = request_head(host_header, port, path, secure, "*/*");
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
    execute_http_request_targets_with_accept(
        targets,
        port,
        transport,
        host_header,
        path,
        secure,
        key_log,
        HttpRequestOptions { accept: "*/*", cancel: None },
    )
}

/// Issue a verified HTTPS GET for a vendor JSON DoH endpoint.
pub fn execute_doh_json_request(
    target: &TargetAddress,
    transport: &TransportConfig,
    host: &str,
    path: &str,
    cancel: &AtomicBool,
) -> Result<HttpResponse, String> {
    execute_http_request_targets_with_accept(
        std::slice::from_ref(target),
        443,
        transport,
        host,
        path,
        true,
        None,
        HttpRequestOptions { accept: "application/dns-json", cancel: Some(cancel) },
    )
}

struct HttpRequestOptions<'a> {
    accept: &'a str,
    cancel: Option<&'a AtomicBool>,
}

fn execute_http_request_targets_with_accept(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
    request_options: HttpRequestOptions<'_>,
) -> Result<HttpResponse, String> {
    let HttpRequestOptions { accept, cancel } = request_options;
    let tls_name = if secure { Some(host_header) } else { None };
    let options = ProbeStreamOptions {
        verify_certificates: secure,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: None,
        key_log,
    };
    let mut stream =
        open_probe_stream_targets_with_options_and_abort(targets, port, transport, tls_name, &options, &|| {
            cancel.and_then(abort_reason)
        })
        .map_err(|err| err.to_string())?
        .stream;
    let request = request_head(host_header, port, path, secure, accept);
    let exchange = |stream: &mut ConnectionStream| {
        if let Some(reason) = cancel.and_then(abort_reason) {
            return Err(reason.to_string());
        }
        stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
        stream.flush().map_err(|err| err.to_string())?;
        read_http_response(stream, MAX_HTTP_BYTES)
    };
    let response = match cancel {
        Some(cancel) => run_with_abort(&mut stream, cancel, exchange)?,
        None => exchange(&mut stream)?,
    };
    stream.shutdown();
    Ok(response)
}

fn run_with_abort<T>(
    stream: &mut ConnectionStream,
    cancel: &AtomicBool,
    operation: impl FnOnce(&mut ConnectionStream) -> Result<T, String>,
) -> Result<T, String> {
    if let Some(reason) = abort_reason(cancel) {
        return Err(reason.to_string());
    }
    let socket = match stream {
        ConnectionStream::Plain(socket) => socket.try_clone(),
        ConnectionStream::Tls(tls) => tls.sock.try_clone(),
    }
    .map_err(|err| err.to_string())?;
    let deadline = active_scan_io_deadline();
    let (done_tx, done_rx) = mpsc::channel();
    let result = std::thread::scope(|scope| {
        scope.spawn(move || {
            loop {
                if cancel.load(Ordering::Acquire) || deadline.is_some_and(|value| Instant::now() >= value) {
                    let _ = socket.shutdown(Shutdown::Both);
                    break;
                }
                let wait = deadline
                    .map_or(Duration::from_millis(50), |value| value.saturating_duration_since(Instant::now()))
                    .min(Duration::from_millis(50));
                match done_rx.recv_timeout(wait) {
                    Ok(()) | Err(RecvTimeoutError::Disconnected) => break,
                    Err(RecvTimeoutError::Timeout) => {}
                }
            }
        });
        let result = operation(stream);
        let _ = done_tx.send(());
        result
    });
    abort_reason(cancel).map_or(result, |reason| Err(reason.to_string()))
}

fn abort_reason(cancel: &AtomicBool) -> Option<&'static str> {
    if cancel.load(Ordering::Acquire) {
        Some("scan_cancelled")
    } else if active_scan_io_deadline().is_some_and(|value| Instant::now() >= value) {
        Some("scan_deadline_exceeded")
    } else {
        None
    }
}

fn request_head(host: &str, port: u16, path: &str, secure: bool, accept: &str) -> String {
    let mut authority = if host.parse::<std::net::Ipv6Addr>().is_ok() { format!("[{host}]") } else { host.to_string() };
    if port != if secure { 443 } else { 80 } {
        authority.push_str(&format!(":{port}"));
    }
    format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nAccept: {accept}\r\nConnection: close\r\n\r\n")
}

#[cfg(test)]
mod tests {
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use super::{request_head, run_with_abort};
    use crate::http::read_http_response;
    use crate::transport::ConnectionStream;

    #[test]
    fn json_request_selects_vendor_json_without_changing_other_requests() {
        let path = "/dns-query?name=example.org&type=A&ct=application/dns-json";
        assert!(
            request_head("cloudflare-dns.com", 443, path, true, "application/dns-json")
                .contains("Accept: application/dns-json\r\n")
        );
        assert!(request_head("example.org", 443, "/", true, "*/*").contains("Accept: */*\r\n"));
    }

    #[test]
    fn in_flight_json_io_stops_on_deadline_or_cancellation() {
        for cancellation in [false, true] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut stream = ConnectionStream::Plain(socket);
            let _peer = listener.accept().unwrap();
            let cancel = AtomicBool::new(false);
            let started = Instant::now();
            let result = std::thread::scope(|scope| {
                if cancellation {
                    scope.spawn(|| {
                        std::thread::sleep(Duration::from_millis(20));
                        cancel.store(true, Ordering::Release);
                    });
                }
                with_scan_io_deadline((!cancellation).then(|| Instant::now() + Duration::from_millis(20)), || {
                    run_with_abort(&mut stream, &cancel, |stream| read_http_response(stream, 1024))
                })
            });
            assert_eq!(result.unwrap_err(), if cancellation { "scan_cancelled" } else { "scan_deadline_exceeded" });
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }

    #[test]
    fn cancelled_json_exchange_starts_no_io() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut stream = ConnectionStream::Plain(TcpStream::connect(listener.local_addr().unwrap()).unwrap());
        let _peer = listener.accept().unwrap();
        let cancel = AtomicBool::new(true);
        let result = run_with_abort(&mut stream, &cancel, |_| -> Result<(), String> { panic!("unexpected I/O") });
        assert_eq!(result.unwrap_err(), "scan_cancelled");
    }
}
