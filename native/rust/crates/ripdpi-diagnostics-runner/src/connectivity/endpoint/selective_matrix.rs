use std::io::{Read, Write};
use std::net::{IpAddr, Shutdown};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, bounded_scan_io_timeout, with_scan_io_deadline};
use ripdpi_diagnostics_contracts::{ProbeDetail, ProbeResult, SelectiveMatrixConfig, SelectiveMatrixTarget};
use ripdpi_diagnostics_tls::tls::{
    ApplicationProtocolPolicy, ProbeStreamFailureStage, ProbeStreamOptions, is_certificate_error,
    open_probe_stream_targets_with_options_and_abort,
};
use ripdpi_diagnostics_transport::transport::{ConnectionStream, TargetAddress, TransportConfig, resolve_addresses};
use rustls::client::danger::ServerCertVerifier;

use super::{matrix_body, target_parse::parse_http_target};

pub fn run_selective_matrix_attempt(
    config: &SelectiveMatrixConfig,
    target: &SelectiveMatrixTarget,
    attempt: usize,
    transport: &TransportConfig,
    cancel: &AtomicBool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    let started = Instant::now();
    let own = started + Duration::from_millis(config.timeout_ms.min(10000));
    let scan_deadline = active_scan_io_deadline();
    let deadline = scan_deadline.map_or(own, |outer| outer.min(own));
    let mut result = base_result(config, target, attempt);
    with_scan_io_deadline(Some(deadline), || {
        let control = AttemptControl { cancel, deadline, scan_deadline };
        if config.validate().is_err() {
            return;
        }
        if control.aborted() {
            fail(
                &mut result,
                "dns",
                if control.cancelled() { "matrix_target_cancelled" } else { "matrix_target_transport_failed" },
            );
            return;
        }
        let Ok(parsed) = parse_http_target(&target.url, None, &[], None) else {
            return;
        };
        if !parsed.secure || parsed.port != 443 {
            return;
        }
        let dns_start = Instant::now();
        // A bounded resolver executor resolves once. Both direct and SOCKS use only
        // admitted IPs, preserving the original name solely for TLS and HTTP.
        let addresses = if target.connect_ips.is_empty() {
            resolve_addresses(&TargetAddress::Host(parsed.host.clone()), parsed.port)
                .map(|v| v.into_iter().map(|a| a.ip()).collect::<Vec<_>>())
                .ok()
        } else {
            target.connect_ips.iter().map(|value| value.parse::<IpAddr>().ok()).collect::<Option<Vec<_>>>()
        };
        put(&mut result, "dnsElapsedMs", dns_start.elapsed().as_millis().to_string());
        let Some(addresses) = addresses.filter(|ips| !ips.is_empty()) else {
            fail(
                &mut result,
                "dns",
                if control.cancelled() { "matrix_target_cancelled" } else { "matrix_target_transport_failed" },
            );
            return;
        };
        if addresses.iter().any(|ip| !public_ip(*ip)) {
            fail(&mut result, "dns", "matrix_target_invalid");
            return;
        }
        put(&mut result, "dnsStatus", if target.connect_ips.is_empty() { "ok" } else { "admitted_addresses" });
        if control.aborted() {
            fail(
                &mut result,
                "dns",
                if control.cancelled() { "matrix_target_cancelled" } else { "matrix_target_transport_failed" },
            );
            return;
        }
        let targets: Vec<_> = addresses.into_iter().map(TargetAddress::Ip).collect();
        execute_https(&parsed, &targets, config.max_response_bytes, transport, &control, verifier, &mut result);
    });
    put(&mut result, "elapsedMs", started.elapsed().as_millis().to_string());
    result
}

fn execute_https(
    parsed: &super::types::ParsedHttpTarget,
    targets: &[TargetAddress],
    max_response_bytes: usize,
    transport: &TransportConfig,
    control: &AttemptControl<'_>,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    result: &mut ProbeResult,
) {
    let options = ProbeStreamOptions {
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: verifier,
        ..ProbeStreamOptions::default()
    };
    let opened = open_probe_stream_targets_with_options_and_abort(
        targets,
        parsed.port,
        transport,
        Some(&parsed.host),
        &options,
        &|| control.aborted().then_some("interrupted"),
    );
    let mut opened = match opened {
        Ok(value) => value,
        Err(error) => {
            if error.tcp_connect_ms.is_some() {
                put(result, "tcpStatus", "ok");
            }
            let stage = if matches!(error.stage, ProbeStreamFailureStage::TlsHandshake) { "tls" } else { "tcp" };
            fail(
                result,
                stage,
                if control.cancelled() {
                    "matrix_target_cancelled"
                } else if is_certificate_error(&error.message) {
                    "matrix_target_tls_error"
                } else {
                    "matrix_target_transport_failed"
                },
            );
            return;
        }
    };
    put(result, "tcpStatus", "ok");
    put(result, "tlsStatus", "ok");
    let write_timeout = control
        .deadline
        .saturating_duration_since(Instant::now())
        .min(Duration::from_millis(200))
        .max(Duration::from_millis(1));
    let timeout_result = match &opened.stream {
        ConnectionStream::Plain(s) => s.set_write_timeout(Some(write_timeout)),
        ConnectionStream::Tls(s) => s.sock.set_write_timeout(Some(write_timeout)),
    };
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
        parsed.path, parsed.host
    );
    if control.aborted()
        || timeout_result.is_err()
        || opened.stream.write_all(request.as_bytes()).and_then(|_| opened.stream.flush()).is_err()
    {
        fail(
            result,
            "http",
            if control.cancelled() { "matrix_target_cancelled" } else { "matrix_target_body_incomplete" },
        );
    } else {
        let body = matrix_body::read_response(
            &mut Interruptible { stream: &mut opened.stream, abort: &|| control.aborted() },
            max_response_bytes,
            &|| control.aborted(),
        );
        put(result, "httpStatusCode", body.status.to_string());
        put(
            result,
            "httpStatus",
            if body.headers_complete && (200..300).contains(&body.status) { "ok" } else { "failed" },
        );
        put(result, "bodyByteCount", body.bytes.to_string());
        put(result, "bodyComplete", body.complete.to_string());
        put(
            result,
            "bodyStatus",
            if !body.headers_complete || body.status >= 300 {
                "not_run"
            } else if body.complete {
                "ok"
            } else {
                body.reason
            },
        );
        put(result, "bodyReason", body.reason);
        if body.complete {
            result.outcome = "matrix_target_available".into();
        } else {
            fail(
                result,
                if !body.headers_complete || body.status >= 300 { "http" } else { "body" },
                if control.cancelled() {
                    "matrix_target_cancelled"
                } else if body.status >= 300 {
                    "matrix_target_http_error"
                } else {
                    "matrix_target_body_incomplete"
                },
            );
        }
    }
    // Completion does not need another TLS write after the measurement deadline.
    let _ = match &opened.stream {
        ConnectionStream::Plain(s) => s.shutdown(Shutdown::Both),
        ConnectionStream::Tls(s) => s.sock.shutdown(Shutdown::Both),
    };
}

struct AttemptControl<'a> {
    cancel: &'a AtomicBool,
    deadline: Instant,
    scan_deadline: Option<Instant>,
}
impl AttemptControl<'_> {
    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire) || self.scan_deadline.is_some_and(|d| Instant::now() >= d)
    }
    fn aborted(&self) -> bool {
        self.cancelled() || Instant::now() >= self.deadline
    }
}

struct Interruptible<'a> {
    stream: &'a mut ConnectionStream,
    abort: &'a dyn Fn() -> bool,
}
impl Read for Interruptible<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        loop {
            if (self.abort)() {
                return Err(std::io::ErrorKind::Interrupted.into());
            }
            let timeout = bounded_scan_io_timeout(Duration::from_millis(200))
                .map_err(|_| std::io::Error::from(std::io::ErrorKind::TimedOut))?;
            match &self.stream {
                ConnectionStream::Plain(s) => s.set_read_timeout(Some(timeout))?,
                ConnectionStream::Tls(s) => s.sock.set_read_timeout(Some(timeout))?,
            }
            match self.stream.read(buffer) {
                Err(e) if matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock) => continue,
                result => return result,
            }
        }
    }
}

fn public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            let reserved = matches!(a, 0 | 10 | 127 | 224..=255)
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254)
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113);
            !reserved
        }
        IpAddr::V6(ip) => {
            let s = ip.segments();
            (s[0] & 0xe000) == 0x2000
                && !(s[0] == 0x2001 && (s[1] < 0x200 || s[1] == 0xdb8))
                && s[0] != 0x2002
                && !(s[0] == 0x3fff && s[1] < 0x1000)
        }
    }
}
fn put(result: &mut ProbeResult, key: &str, value: impl ToString) {
    if let Some(detail) = result.details.iter_mut().find(|d| d.key == key) {
        detail.value = value.to_string();
    } else {
        result.details.push(ProbeDetail { key: key.into(), value: value.to_string() });
    }
}
fn fail(result: &mut ProbeResult, stage: &str, outcome: &str) {
    put(result, "failureStage", stage);
    put(result, &format!("{stage}Status"), "failed");
    result.outcome = outcome.into();
}
fn base_result(config: &SelectiveMatrixConfig, target: &SelectiveMatrixTarget, attempt: usize) -> ProbeResult {
    let mut result = ProbeResult {
        probe_type: "selective_availability".into(),
        target: target.label.clone(),
        outcome: "matrix_target_invalid".into(),
        details: vec![],
    };
    for (key, value) in [
        ("targetId", target.id.as_str()),
        ("cohort", target.cohort.as_str()),
        ("infrastructureGroup", target.infrastructure_group.as_str()),
        ("catalogVersion", config.catalog_version.as_str()),
        ("sourceUrl", target.source_url.as_str()),
        ("sourceDate", target.source_date.as_str()),
        ("lastVerifiedAt", target.last_verified_at.as_deref().unwrap_or("unknown")),
        ("failureStage", "none"),
        ("bodyComplete", "false"),
        ("bodyByteCount", "0"),
        ("dnsStatus", "not_run"),
        ("tcpStatus", "not_run"),
        ("tlsStatus", "not_run"),
        ("httpStatus", "not_run"),
        ("bodyStatus", "not_run"),
    ] {
        put(&mut result, key, value);
    }
    put(&mut result, "attempt", attempt);
    result
}

#[cfg(test)]
mod ip_tests {
    use super::*;
    #[test]
    fn rejects_private_reserved_and_translation_destinations() {
        for ip in [
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "::1",
            "::ffff:8.8.8.8",
            "64:ff9b::808:808",
            "2001:db8::1",
            "fc00::1",
            "fe80::1",
            "2002:0808:0808::1",
        ] {
            assert!(!public_ip(ip.parse().unwrap()), "{ip}");
        }
        for ip in ["8.8.8.8", "1.1.1.1", "192.0.73.2", "192.2.1.1", "2606:4700:4700::1111"] {
            assert!(public_ip(ip.parse().unwrap()), "{ip}");
        }
    }
}

#[cfg(test)]
mod tests;
