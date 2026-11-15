use std::net::{IpAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};

use ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout;

use crate::tls::{ProbeStreamFailureStage, ProbeStreamResult, TlsKeyLogCallback};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, run_with_socket_abort_watchdog};
use crate::types::TcpTarget;
use crate::util::{FAT_HEADER_REQUESTS, IO_TIMEOUT, fat_threshold_reached};

use super::classification::classify_response_io_error;
use super::request_io::{ResponseHead, effective_host_header, read_response_headers, write_padded_head_request};
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
    if let Some(reason) = stop_reason(cancel) {
        return stopped(FatHeaderCounters::default(), None, reason);
    }

    let connect_target = match parse_connect_target(target) {
        Ok(target) => target,
        Err(err) => {
            let mut observation = FatHeaderObservation::open_failed(FatHeaderStatus::Inconclusive, err);
            observation.failure_stage = Some("target_address");
            return observation;
        }
    };

    let tls_sni = if !sni.is_empty() { Some(sni) } else { None };
    let probe_result =
        match open_fat_header_probe_stream(&connect_target, target.port, transport, tls_sni, key_log, &|| {
            stop_reason(cancel)
        }) {
            Ok(result) => result,
            Err(err) => {
                if let Some(reason) = stop_reason(cancel) {
                    return stopped(FatHeaderCounters::default(), err.transport_open_ms, reason);
                }
                let status = match err.stage {
                    ProbeStreamFailureStage::TcpConnect => FatHeaderStatus::ConnectFailed,
                    ProbeStreamFailureStage::TlsHandshake => FatHeaderStatus::HandshakeFailed,
                    _ => FatHeaderStatus::Inconclusive,
                };
                let mut observation = FatHeaderObservation::open_failed(status, err.message);
                observation.failure_stage = Some(err.stage.as_str());
                observation.transport_open_ms = err.transport_open_ms;
                return observation;
            }
        };

    run_padded_head_sequence(probe_result, target, host_header, cancel)
}

fn parse_connect_target(target: &TcpTarget) -> Result<TargetAddress, String> {
    target.ip.parse::<IpAddr>().map(TargetAddress::Ip).map_err(|err| err.to_string())
}

fn run_padded_head_sequence(
    mut probe_result: ProbeStreamResult,
    target: &TcpTarget,
    host_header: &str,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    let transport_open_ms = Some(probe_result.transport_open_ms);
    let stream = &mut probe_result.stream;
    // Socket timeouts bound one read; the watchdog bounds a peer that drips bytes within each.
    let result = probe_socket(stream).try_clone().and_then(|socket| {
        run_with_socket_abort_watchdog(socket, &|| cancelled(cancel), || {
            Ok(padded_head_sequence(stream, target, host_header, transport_open_ms, cancel))
        })
    });
    let observation = match result {
        Ok(observation) => observation,
        Err(err) => {
            let mut observation = FatHeaderObservation::open_failed(FatHeaderStatus::Inconclusive, err.to_string());
            observation.failure_stage = Some("stream_setup");
            observation.transport_open_ms = transport_open_ms;
            observation
        }
    };
    probe_result.stream.shutdown();
    observation
}

fn padded_head_sequence(
    stream: &mut ConnectionStream,
    target: &TcpTarget,
    host_header: &str,
    transport_open_ms: Option<u64>,
    cancel: Option<&AtomicBool>,
) -> FatHeaderObservation {
    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut counters = FatHeaderCounters::default();
    let mut timing = ProbeTiming::start();
    let host_header = effective_host_header(target, host_header);
    let host_header = host_header.as_str();

    for index in 0..requests {
        if let Some(reason) = stop_reason(cancel) {
            return stopped(counters, transport_open_ms, reason);
        }
        if let Err(err) = arm_socket_timeouts(stream) {
            if let Some(reason) = stop_reason(cancel) {
                return stopped(counters, transport_open_ms, reason);
            }
            let mut observation = FatHeaderObservation::io_failed(
                FatHeaderStatus::Inconclusive,
                err.to_string(),
                transport_open_ms,
                None,
                counters.bytes_sent,
                counters.responses_seen,
            );
            observation.failure_stage = Some("stream_setup");
            return observation;
        }
        if let Err(err) = write_padded_head_request(stream, index, host_header, &mut counters.bytes_sent) {
            if let Some(reason) = stop_reason(cancel) {
                return stopped(counters, transport_open_ms, reason);
            }
            let status = if err.kind() == std::io::ErrorKind::ConnectionReset {
                FatHeaderStatus::Reset
            } else {
                FatHeaderStatus::Inconclusive
            };
            let rst_timing_ms = (status == FatHeaderStatus::Reset).then(|| timing.elapsed_since_success_ms());
            let mut observation = FatHeaderObservation::io_failed(
                status,
                err.to_string(),
                transport_open_ms,
                rst_timing_ms,
                counters.bytes_sent,
                counters.responses_seen,
            );
            observation.failure_stage = Some("request_write");
            return observation;
        }
        timing.mark_io_success();

        match read_response_headers(stream) {
            Ok(head) => {
                counters.responses_seen += 1;
                timing.mark_io_success();
                if let Some(reason) = stop_reason(cancel) {
                    return stopped(counters, transport_open_ms, reason);
                }
                if !head.is_success_class() {
                    return inconclusive_response(counters, head, transport_open_ms);
                }
                if head.closes && index + 1 < requests {
                    if fat_threshold_reached(counters.bytes_sent) {
                        return FatHeaderObservation::success(
                            counters.bytes_sent,
                            counters.responses_seen,
                            transport_open_ms,
                        );
                    }
                    return inconclusive_response(counters, head, transport_open_ms);
                }
            }
            Err(err) => {
                if let Some(reason) = stop_reason(cancel) {
                    return stopped(counters, transport_open_ms, reason);
                }
                let status = classify_response_io_error(err.kind());
                let rst_timing_ms = (status == FatHeaderStatus::Reset).then(|| timing.elapsed_since_success_ms());
                let mut observation = FatHeaderObservation::io_failed(
                    status,
                    err.to_string(),
                    transport_open_ms,
                    rst_timing_ms,
                    counters.bytes_sent,
                    counters.responses_seen,
                );
                observation.failure_stage = Some("response_read");
                return observation;
            }
        }
    }

    FatHeaderObservation::success(counters.bytes_sent, counters.responses_seen, transport_open_ms)
}

fn probe_socket(stream: &ConnectionStream) -> &TcpStream {
    match stream {
        ConnectionStream::Plain(socket) => socket,
        ConnectionStream::Tls(tls) => &tls.sock,
    }
}

/// Clamp the per-operation socket timeouts to the remaining scan time before each request.
fn arm_socket_timeouts(stream: &ConnectionStream) -> std::io::Result<()> {
    let timeout =
        bounded_scan_io_timeout(IO_TIMEOUT).map_err(|err| std::io::Error::new(std::io::ErrorKind::TimedOut, err))?;
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

fn stopped(counters: FatHeaderCounters, transport_open_ms: Option<u64>, reason: &'static str) -> FatHeaderObservation {
    let mut observation = FatHeaderObservation::io_failed(
        FatHeaderStatus::Inconclusive,
        reason.to_string(),
        transport_open_ms,
        None,
        counters.bytes_sent,
        counters.responses_seen,
    );
    observation.failure_stage = Some("administrative_stop");
    observation
}

/// A response that ends the sequence without saying anything about a middlebox cutoff: a
/// 4xx/5xx or a server-announced close before the sequence completes.
fn inconclusive_response(
    counters: FatHeaderCounters,
    head: ResponseHead,
    transport_open_ms: Option<u64>,
) -> FatHeaderObservation {
    let answer = format!("HTTP {} answer", head.status);
    let close = if head.closes { ", server closed the connection," } else { "" };
    let mut observation = FatHeaderObservation::io_failed(
        FatHeaderStatus::Inconclusive,
        format!("{answer}{close} after response {} and {} bytes sent", counters.responses_seen, counters.bytes_sent),
        transport_open_ms,
        None,
        counters.bytes_sent,
        counters.responses_seen,
    );
    observation.failure_stage = Some("http_response");
    observation
}

#[derive(Default)]
struct FatHeaderCounters {
    bytes_sent: usize,
    responses_seen: usize,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::{Shutdown, TcpListener};
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
        let target = TcpTarget {
            id: "loopback".into(),
            provider: "loopback".into(),
            ip: "127.0.0.1".into(),
            port: addr.port(),
            sni: None,
            asn: None,
            host_header: Some(host.into()),
            fat_header_requests: Some(1),
            alt_port: None,
        };

        let obs = padded_head_sequence(&mut ConnectionStream::Plain(socket), &target, host, None, None);
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
