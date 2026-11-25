use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;

use crate::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options_and_abort,
};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, run_with_socket_abort_watchdog};
use crate::util::MAX_HTTP_BYTES;

use super::classifier::classify_http_response_with_fingerprints;
use super::request_error::HttpRequestError;
use super::response_parser::read_http_response_io;
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
    try_http_request_targets_with_key_log(
        std::slice::from_ref(target),
        port,
        transport,
        host_header,
        path,
        secure,
        key_log,
    )
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
    observe_http_result(execute_http_request_targets_with_key_log(
        targets,
        port,
        transport,
        host_header,
        path,
        secure,
        key_log,
    ))
}

pub fn try_http_request_targets_with_cancel(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: Option<&AtomicBool>,
) -> HttpObservation {
    observe_http_result(
        execute_http_request_targets_with_accept(
            targets,
            port,
            transport,
            host_header,
            path,
            secure,
            key_log,
            HttpRequestOptions { accept: "*/*", cancel },
        )
        .map_err(|error| error.to_string()),
    )
}

fn observe_http_result(result: Result<HttpResponse, String>) -> HttpObservation {
    match result {
        Ok(response) => HttpObservation {
            status: classify_with_bundled_fingerprints(&response),
            response: Some(response),
            error: None,
        },
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

fn classify_with_bundled_fingerprints(response: &HttpResponse) -> String {
    classify_http_response_with_fingerprints(response, ripdpi_failure_classifier::bundled_blockpage_fingerprints()).0
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
    execute_http_request_targets_with_key_log(
        std::slice::from_ref(target),
        port,
        transport,
        host_header,
        path,
        secure,
        key_log,
    )
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
    .map_err(|error| error.to_string())
}

pub fn execute_doh_json_request(
    target: &TargetAddress,
    transport: &TransportConfig,
    host: &str,
    path: &str,
    cancel: &AtomicBool,
) -> Result<HttpResponse, HttpRequestError> {
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
) -> Result<HttpResponse, HttpRequestError> {
    let HttpRequestOptions { accept, cancel } = request_options;
    let tls_name = if secure { Some(host_header) } else { None };
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: None.into(),
        key_log,
    };
    let mut stream =
        open_probe_stream_targets_with_options_and_abort(targets, port, transport, tls_name, &options, &|| {
            abort_reason(cancel)
        })
        .map_err(|error| abort_reason(cancel).map_or(HttpRequestError::Stream(error), abort_error))?
        .stream;
    let request = request_head(host_header, port, path, secure, accept);
    let exchange = |stream: &mut ConnectionStream| {
        if let Some(reason) = abort_reason(cancel) {
            return Err(abort_error(reason));
        }
        stream.write_all(request.as_bytes()).map_err(HttpRequestError::RequestWrite)?;
        stream.flush().map_err(HttpRequestError::RequestWrite)?;
        read_http_response_io(stream, MAX_HTTP_BYTES).map_err(HttpRequestError::ResponseRead)
    };
    let response = run_with_abort(&mut stream, cancel, exchange)?;
    stream.shutdown();
    Ok(response)
}

fn run_with_abort<T>(
    stream: &mut ConnectionStream,
    cancel: Option<&AtomicBool>,
    operation: impl FnOnce(&mut ConnectionStream) -> Result<T, HttpRequestError>,
) -> Result<T, HttpRequestError> {
    if let Some(reason) = abort_reason(cancel) {
        return Err(abort_error(reason));
    }
    if cancel.is_none() && active_scan_io_deadline().is_none() {
        return operation(stream);
    }
    let socket = match stream {
        ConnectionStream::Plain(socket) => socket.try_clone(),
        ConnectionStream::Tls(tls) => tls.sock.try_clone(),
    }
    .map_err(HttpRequestError::SocketSetup)?;
    let cancelled = || cancel.is_some_and(|flag| flag.load(Ordering::Acquire));
    let result = run_with_socket_abort_watchdog(socket, &cancelled, || Ok(operation(stream)));
    if let Some(reason) = abort_reason(cancel) {
        return Err(abort_error(reason));
    }
    result.map_err(HttpRequestError::SocketSetup)?
}

fn abort_error(reason: &'static str) -> HttpRequestError {
    if reason == "scan_cancelled" { HttpRequestError::Cancelled } else { HttpRequestError::DeadlineExceeded }
}

fn abort_reason(cancel: Option<&AtomicBool>) -> Option<&'static str> {
    if cancel.is_some_and(|flag| flag.load(Ordering::Acquire)) {
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
    format!(
        "GET {path} HTTP/1.1\r\nHost: {authority}\r\nAccept: {accept}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
    )
}

#[cfg(test)]
mod tests {
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use std::io::{Read, Write};

    use super::{HttpRequestError, read_http_response_io, request_head, run_with_abort, try_http_request};
    use crate::transport::{ConnectionStream, TargetAddress, direct_transport};

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
                    run_with_abort(&mut stream, Some(&cancel), |stream| {
                        read_http_response_io(stream, 1024).map_err(HttpRequestError::ResponseRead)
                    })
                })
            });
            let error = result.unwrap_err();
            assert!(matches!(
                (&error, cancellation),
                (HttpRequestError::Cancelled, true) | (HttpRequestError::DeadlineExceeded, false)
            ));
            assert_eq!(error.to_string(), if cancellation { "scan_cancelled" } else { "scan_deadline_exceeded" });
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }

    #[test]
    fn stalled_json_http_response_retains_timeout_kind_and_stage() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let peer = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut request = Vec::new();
            socket.read_to_end(&mut request).unwrap();
            assert!(request.starts_with(b"GET /resolve?name=example.test&type=A HTTP/1.1\r\n"));
        });
        let cancel = AtomicBool::new(false);
        let error = super::execute_http_request_targets_with_accept(
            &[TargetAddress::Ip(std::net::Ipv4Addr::LOCALHOST.into())],
            port,
            &direct_transport(),
            "localhost",
            "/resolve?name=example.test&type=A",
            false,
            None,
            super::HttpRequestOptions { accept: "application/dns-json", cancel: Some(&cancel) },
        )
        .unwrap_err();
        assert_eq!(error.status(), "timeout");
        match error {
            HttpRequestError::ResponseRead(source) => {
                assert!(matches!(source.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut));
            }
            other => panic!("expected a response-read timeout, got {other:?}"),
        }
        peer.join().unwrap();
    }

    #[test]
    fn slow_drip_http_response_stops_at_the_scan_deadline() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let _ = socket.read(&mut [0u8; 1024]);
            let started = Instant::now();
            for byte in b"HTTP/1.1 200 OK\r\nX-Slow: ".iter().chain(std::iter::repeat(&b'a')) {
                if started.elapsed() > Duration::from_secs(4) || socket.write_all(&[*byte]).is_err() {
                    return;
                }
                std::thread::sleep(Duration::from_millis(100));
            }
        });
        let started = Instant::now();

        let observation = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(400)), || {
            try_http_request(
                &TargetAddress::Ip(std::net::Ipv4Addr::LOCALHOST.into()),
                port,
                &direct_transport(),
                "localhost",
                "/",
                false,
            )
        });

        let elapsed = started.elapsed();
        assert_eq!(observation.status, "http_unreachable");
        assert_eq!(observation.error.as_deref(), Some("scan_deadline_exceeded"));
        assert!(elapsed < Duration::from_millis(1500), "request ran for {elapsed:?}");
        server.join().unwrap();
    }

    #[test]
    fn cancelled_json_exchange_starts_no_io() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut stream = ConnectionStream::Plain(TcpStream::connect(listener.local_addr().unwrap()).unwrap());
        let _peer = listener.accept().unwrap();
        let cancel = AtomicBool::new(true);
        let result = run_with_abort(&mut stream, Some(&cancel), |_| -> Result<(), HttpRequestError> {
            panic!("unexpected I/O")
        });
        assert_eq!(result.unwrap_err().to_string(), "scan_cancelled");
    }
}

#[cfg(test)]
#[path = "content_coding_tests.rs"]
mod content_coding_tests;
