use std::io::{ErrorKind, Read, Write};
use std::net::IpAddr;
use std::sync::Arc;

use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use rustls::client::danger::ServerCertVerifier;

use crate::http::*;
use crate::tls::*;
use crate::transport::*;
use crate::types::*;
use crate::util::*;

#[derive(Clone)]
pub(super) struct ThroughputSample {
    pub(super) status: String,
    pub(super) bytes_read: usize,
    pub(super) bps: u64,
    pub(super) error: String,
}

pub(super) struct EndpointProbeObservation {
    pub(super) status: String,
    pub(super) error: String,
    pub(super) local_addr: Option<std::net::SocketAddr>,
    pub(super) route_report: Option<RouteExperimentReport>,
}

struct ParsedHttpTarget {
    host: String,
    path: String,
    port: u16,
    secure: bool,
    connect_targets: Vec<TargetAddress>,
}

pub(super) fn measure_throughput_window(target: &ThroughputTarget, transport: &TransportConfig) -> ThroughputSample {
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), &target.connect_ips, target.port) {
        Ok(parsed) => parsed,
        Err(err) => {
            return ThroughputSample { status: "invalid_target".to_string(), bytes_read: 0, bps: 0, error: err }
        }
    };
    let started = std::time::Instant::now();
    let mut stream = match open_probe_stream_targets(
        &parsed.connect_targets,
        parsed.port,
        transport,
        if parsed.secure { Some(parsed.host.as_str()) } else { None },
        parsed.secure,
        TlsClientProfile::Auto,
        None,
    ) {
        Ok(result) => result.stream,
        Err(err) => {
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err }
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
) -> HttpObservation {
    match parse_http_target(url, connect_ip, connect_ips, port_override) {
        Ok(parsed) => try_http_request_targets(
            &parsed.connect_targets,
            parsed.port,
            transport,
            &parsed.host,
            &parsed.path,
            parsed.secure,
        ),
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

pub(super) fn run_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    tls_name: Option<&str>,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> EndpointProbeObservation {
    let Some(target) = connect_target_from_parts(host, connect_ip) else {
        return EndpointProbeObservation {
            status: "not_run".to_string(),
            error: "not_run".to_string(),
            local_addr: None,
            route_report: None,
        };
    };
    if tls_name.is_some() || port == 443 {
        let server_name = tls_name.or(host).unwrap_or_default();
        let observation =
            try_tls_handshake(&target, port, transport, server_name, true, TlsClientProfile::Auto, tls_verifier);
        EndpointProbeObservation {
            status: observation.status,
            error: observation.error.unwrap_or_else(|| "none".to_string()),
            local_addr: observation.local_addr,
            route_report: observation.route_report,
        }
    } else {
        match connect_transport_observed(std::slice::from_ref(&target), port, transport) {
            Ok(result) => {
                let stream = result.stream;
                let _ = stream.shutdown(std::net::Shutdown::Both);
                EndpointProbeObservation {
                    status: "tcp_connect_ok".to_string(),
                    error: "none".to_string(),
                    local_addr: result.local_addr,
                    route_report: result.route_report,
                }
            }
            Err(err) => EndpointProbeObservation {
                status: "tcp_connect_failed".to_string(),
                error: err,
                local_addr: None,
                route_report: None,
            },
        }
    }
}

pub(super) fn run_quic_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    transport: &TransportConfig,
) -> EndpointProbeObservation {
    let Some(host_name) = host else {
        return EndpointProbeObservation {
            status: "not_run".to_string(),
            error: "not_run".to_string(),
            local_addr: None,
            route_report: None,
        };
    };
    let connect_target = connect_target_from_parts(Some(host_name), connect_ip)
        .unwrap_or_else(|| TargetAddress::Host(host_name.to_string()));
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(host_name)).unwrap_or_default();
    match relay_udp_payload_observed(std::slice::from_ref(&connect_target), port, transport, &payload) {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => EndpointProbeObservation {
            status: "quic_initial_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) if !result.payload.is_empty() => EndpointProbeObservation {
            status: "quic_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) => EndpointProbeObservation {
            status: "quic_empty".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Err(err) => EndpointProbeObservation {
            status: "quic_error".to_string(),
            error: err,
            local_addr: None,
            route_report: None,
        },
    }
}

fn parse_http_target(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
) -> Result<ParsedHttpTarget, String> {
    let secure = url.starts_with("https://");
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .ok_or_else(|| "unsupported_url_scheme".to_string())?;
    let (authority, path) = match without_scheme.split_once('/') {
        Some((authority, suffix)) => (authority, format!("/{suffix}")),
        None => (without_scheme, "/".to_string()),
    };
    let (host, parsed_port) = split_host_and_port(authority);
    if host.is_empty() {
        return Err("missing_url_host".to_string());
    }
    let port = port_override.or(parsed_port).unwrap_or(if secure { 443 } else { 80 });
    let connect_targets = throughput_connect_targets(Some(host.as_str()), connect_ip, connect_ips);
    Ok(ParsedHttpTarget { host, path, port, secure, connect_targets })
}

fn split_host_and_port(authority: &str) -> (String, Option<u16>) {
    if authority.starts_with('[') {
        return (authority.to_string(), None);
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse::<u16>() {
            Ok(parsed_port) => (host.to_string(), Some(parsed_port)),
            Err(_) => (authority.to_string(), None),
        },
        None => (authority.to_string(), None),
    }
}

fn connect_target_from_parts(host: Option<&str>, connect_ip: Option<&str>) -> Option<TargetAddress> {
    connect_ip
        .and_then(|value| value.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .or_else(|| host.filter(|value| !value.is_empty()).map(|value| TargetAddress::Host(value.to_string())))
}

pub(super) fn is_probe_failure(status: &str) -> bool {
    if status.starts_with("http_status_3") {
        return false; // 3xx redirects are not probe failures
    }
    !matches!(
        status,
        "not_run"
            | "http_ok"
            | "http_redirect"
            | "tls_ok"
            | "tcp_connect_ok"
            | "quic_initial_response"
            | "quic_response"
    )
}

/// Returns true for HTTP status codes that indicate the server is reachable
/// but returned a non-success response (rate limits, server errors, etc.).
/// These should NOT be classified as censorship blocking since the server responded.
pub(super) fn is_server_error(status: &str) -> bool {
    status.starts_with("http_status_4") || status.starts_with("http_status_5")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_probe_failure_false_for_success_outcomes() {
        assert!(!is_probe_failure("http_ok"));
        assert!(!is_probe_failure("tls_ok"));
        assert!(!is_probe_failure("http_redirect"));
        assert!(!is_probe_failure("not_run"));
        assert!(!is_probe_failure("tcp_connect_ok"));
        assert!(!is_probe_failure("quic_initial_response"));
        assert!(!is_probe_failure("quic_response"));
    }

    #[test]
    fn is_probe_failure_false_for_3xx_redirects() {
        assert!(!is_probe_failure("http_status_301"));
        assert!(!is_probe_failure("http_status_302"));
        assert!(!is_probe_failure("http_status_307"));
        assert!(!is_probe_failure("http_status_308"));
    }

    #[test]
    fn is_probe_failure_true_for_failures() {
        assert!(is_probe_failure("http_unreachable"));
        assert!(is_probe_failure("http_blockpage"));
        assert!(is_probe_failure("tls_handshake_failed"));
        assert!(is_probe_failure("http_status_429"));
        assert!(is_probe_failure("http_status_500"));
    }

    #[test]
    fn is_server_error_detects_4xx_and_5xx() {
        assert!(is_server_error("http_status_429"));
        assert!(is_server_error("http_status_400"));
        assert!(is_server_error("http_status_500"));
        assert!(is_server_error("http_status_502"));
        assert!(is_server_error("http_status_503"));
    }

    #[test]
    fn is_server_error_false_for_non_server_errors() {
        assert!(!is_server_error("http_ok"));
        assert!(!is_server_error("http_blockpage"));
        assert!(!is_server_error("http_unreachable"));
        assert!(!is_server_error("http_status_301"));
        assert!(!is_server_error("tls_handshake_failed"));
    }
}
