use std::io::ErrorKind;
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};

use ripdpi_diagnostics_contracts::util::{FAT_HEADER_MAX_REQUESTS, bounded_scan_io_timeout};

use crate::tls::{ProbeStreamFailureStage, ProbeStreamResult, TlsKeyLogCallback};
use crate::transport::{ConnectionStream, ProbeStream, TargetAddress, TransportConfig, run_with_socket_abort_watchdog};
use crate::types::TcpTarget;
use crate::util::{FAT_HEADER_REQUESTS, FAT_HEADER_THRESHOLD_BYTES, IO_TIMEOUT};

use super::classification::classify_response_io_error;
use super::request_io::{effective_host_header, read_response_headers, write_padded_head_request};
use super::status_types::{FatHeaderObservation, FatHeaderStatus};
use super::timing::ProbeTiming;
use super::tls_setup::open_fat_header_probe_stream;

pub fn run_fat_header_attempt(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
) -> FatHeaderObservation {
    run_fat_header_attempt_with_key_log(target, transport, sni, host_header, None, None)
}

pub fn run_fat_header_attempt_with_key_log(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS);
    let mut observation = FatHeaderObservation::pending(requests);
    if let Some(reason) = stop_reason(cancel) {
        return observation.failed(FatHeaderStatus::Inconclusive, "administrative_stop", reason);
    }
    if !(1..=FAT_HEADER_MAX_REQUESTS).contains(&requests) {
        return observation.failed(FatHeaderStatus::Inconclusive, "target_request", "invalid_fat_header_request_count");
    }
    let connect_target = match target.ip.parse::<IpAddr>() {
        Ok(ip) => TargetAddress::Ip(ip),
        Err(err) => return observation.failed(FatHeaderStatus::Inconclusive, "target_address", err.to_string()),
    };
    let tls_sni = (!sni.is_empty()).then_some(sni);
    match open_fat_header_probe_stream(&connect_target, target.port, transport, tls_sni, key_log, &|| {
        stop_reason(cancel)
    }) {
        Ok(stream) => run_padded_head_sequence(stream, target, host_header, observation, cancel),
        Err(err) => {
            observation.transport_open_ms = err.transport_open_ms;
            if let Some(reason) = stop_reason(cancel) {
                return observation.failed(FatHeaderStatus::Inconclusive, "administrative_stop", reason);
            }
            let status = match err.stage {
                ProbeStreamFailureStage::TcpConnect => FatHeaderStatus::ConnectFailed,
                ProbeStreamFailureStage::TlsHandshake => FatHeaderStatus::HandshakeFailed,
                _ => FatHeaderStatus::Inconclusive,
            };
            observation.failed(status, err.stage.as_str(), err.message)
        }
    }
}

fn run_padded_head_sequence(
    mut probe_result: ProbeStreamResult,
    target: &TcpTarget,
    host_header: &str,
    mut observation: FatHeaderObservation,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    observation.transport_open_ms = Some(probe_result.transport_open_ms);
    let stream = &mut probe_result.stream;
    let host_header = effective_host_header(target, host_header);
    // Socket timeouts bound one read. The watchdog bounds a peer that drip feeds the header.
    let result = probe_socket(stream).try_clone().and_then(|socket| {
        run_with_socket_abort_watchdog(socket, &|| cancelled(cancel), || {
            Ok(padded_head_sequence(stream, &host_header, observation.clone(), cancel))
        })
    });
    probe_result.stream.shutdown();
    result.unwrap_or_else(|err| observation.failed(FatHeaderStatus::Inconclusive, "stream_setup", err.to_string()))
}

fn padded_head_sequence(
    stream: &mut ConnectionStream,
    host_header: &str,
    mut observation: FatHeaderObservation,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    let mut timing = ProbeTiming::start();
    for index in 0..observation.requests_planned {
        if let Some(reason) = stop_reason(cancel) {
            return observation.failed(FatHeaderStatus::Inconclusive, "administrative_stop", reason);
        }
        if let Err(err) = arm_socket_timeouts(stream) {
            return io_failed(observation, err, "stream_setup", &timing, cancel);
        }
        if let Err(err) = write_padded_head_request(stream, index, host_header, &mut observation.bytes_sent) {
            return io_failed(observation, err, "request_write", &timing, cancel);
        }
        observation.requests_written += 1;
        timing.mark_io_success();

        let head = match read_response_headers(stream) {
            Ok(head) => head,
            Err(err) => return io_failed(observation, err, "response_read", &timing, cancel),
        };
        observation.responses_seen += 1;
        observation.bytes_at_last_response = observation.bytes_sent;
        observation.http_status = Some(head.status);
        observation.threshold_covered |=
            head.is_success_class() && observation.bytes_at_last_response >= FAT_HEADER_THRESHOLD_BYTES;
        timing.mark_io_success();

        if let Some(reason) = stop_reason(cancel) {
            return observation.failed(FatHeaderStatus::Inconclusive, "administrative_stop", reason);
        }
        if !head.is_success_class() {
            let close = if head.closes { " and announced close" } else { "" };
            return observation.failed(
                FatHeaderStatus::Inconclusive,
                "http_response",
                format!("HTTP {} answer{close}", head.status),
            );
        }
        if head.closes && observation.responses_seen < observation.requests_planned {
            return observation.failed(FatHeaderStatus::Inconclusive, "coverage", "peer_closed_before_series_end");
        }
    }

    if !observation.threshold_covered {
        return observation.failed(FatHeaderStatus::Inconclusive, "coverage", "threshold_not_covered");
    }
    observation.status = FatHeaderStatus::Success;
    observation
}

fn io_failed(
    mut observation: FatHeaderObservation,
    err: std::io::Error,
    stage: &'static str,
    timing: &ProbeTiming,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    if let Some(reason) = stop_reason(cancel) {
        return observation.failed(FatHeaderStatus::Inconclusive, "administrative_stop", reason);
    }
    let status = if stage == "response_read" {
        classify_response_io_error(err.kind())
    } else if stage == "request_write" && err.kind() == ErrorKind::ConnectionReset {
        FatHeaderStatus::Reset
    } else {
        FatHeaderStatus::Inconclusive
    };
    if status == FatHeaderStatus::Reset {
        observation.rst_timing_ms = Some(timing.elapsed_since_success_ms());
    }
    observation.failed(status, stage, err.to_string())
}

fn probe_socket(stream: &ConnectionStream) -> &ProbeStream {
    match stream {
        ConnectionStream::Plain(socket) => socket,
        ConnectionStream::Tls(tls) => &tls.sock,
    }
}

fn arm_socket_timeouts(stream: &ConnectionStream) -> std::io::Result<()> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(|err| std::io::Error::new(ErrorKind::TimedOut, err))?;
    let socket = probe_socket(stream);
    socket.set_read_timeout(Some(timeout))?;
    socket.set_write_timeout(Some(timeout))
}

fn cancelled(cancel: Option<&AtomicBool>) -> bool {
    cancel.is_some_and(|flag| flag.load(Ordering::Acquire))
}

fn stop_reason(cancel: Option<&AtomicBool>) -> Option<&'static str> {
    if cancelled(cancel) { Some("scan_cancelled") } else { bounded_scan_io_timeout(IO_TIMEOUT).err() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::time::Duration;

    #[test]
    fn failed_write_counts_no_unsent_bytes() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let socket = TcpStream::connect(addr).unwrap();
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        socket.shutdown(Shutdown::Write).unwrap();
        let host = "control.test";
        let obs = padded_head_sequence(
            &mut ConnectionStream::Plain(socket.into()),
            host,
            FatHeaderObservation::pending(1),
            None,
        );
        let mut received = Vec::new();
        peer.read_to_end(&mut received).unwrap();
        assert!(received.is_empty());
        assert_eq!(obs.bytes_sent, 0);
        assert_eq!(obs.responses_seen, 0);
        assert!(obs.error.is_some());
        assert_eq!(obs.status, FatHeaderStatus::Inconclusive);
        assert_eq!(obs.failure_stage, Some("request_write"));
    }
}
