use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;

use crate::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options_and_abort,
};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, run_with_socket_watchdog};
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
            abort_reason(cancel)
        })
        .map_err(|err| err.to_string())?
        .stream;
    let request = request_head(host_header, port, path, secure, accept);
    let exchange = |stream: &mut ConnectionStream| {
        if let Some(reason) = abort_reason(cancel) {
            return Err(reason.to_string());
        }
        stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
        stream.flush().map_err(|err| err.to_string())?;
        read_http_response(stream, MAX_HTTP_BYTES)
    };
    let response = run_with_abort(&mut stream, cancel, exchange)?;
    stream.shutdown();
    Ok(response)
}

/// Run one request/response exchange that stops at the scan deadline or on `cancel`.
///
/// The response loops read until EOF, a socket timeout or the size cap. A peer that sends a byte
/// within every socket timeout would keep them going, so a watchdog shuts the socket down at
/// the deadline or on cancel. A failure after that reports the abort reason.
fn run_with_abort<T>(
    stream: &mut ConnectionStream,
    cancel: Option<&AtomicBool>,
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
    run_with_socket_watchdog(socket, cancel, || operation(stream))
        .map_err(|err| abort_reason(cancel).map_or(err, str::to_string))
}

fn abort_reason(cancel: Option<&AtomicBool>) -> Option<&'static str> {
    // Acquire observes the scan owner's published cancellation flag.
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
    format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nAccept: {accept}\r\nConnection: close\r\n\r\n")
}

#[cfg(test)]
mod tests {
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use std::io::{Read, Write};

    use super::{request_head, run_with_abort, try_http_request};
    use crate::http::read_http_response;
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
                    run_with_abort(&mut stream, Some(&cancel), |stream| read_http_response(stream, 1024))
                })
            });
            assert_eq!(result.unwrap_err(), if cancellation { "scan_cancelled" } else { "scan_deadline_exceeded" });
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }

    #[test]
    fn slow_drip_http_response_stops_at_the_scan_deadline() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let _ = socket.read(&mut [0u8; 1024]);
            // One header byte every 100 ms: every read returns well within the socket timeout.
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
        let result = run_with_abort(&mut stream, Some(&cancel), |_| -> Result<(), String> { panic!("unexpected I/O") });
        assert_eq!(result.unwrap_err(), "scan_cancelled");
    }
}
