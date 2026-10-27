use std::net::{IpAddr, TcpStream};

use ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout;

use crate::tls::ProbeStreamResult;
use crate::tls::TlsKeyLogCallback;
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, run_with_socket_watchdog};
use crate::types::TcpTarget;
use crate::util::{FAT_HEADER_REQUESTS, IO_TIMEOUT, fat_threshold_reached};

use super::classification::classify_fat_io_error;
use super::request_io::{
    ResponseHead, effective_host_header, padded_head_request_len, read_response_headers, write_padded_head_request,
};
use super::status_types::{FatHeaderObservation, FatHeaderStatus};
use super::timing::ProbeTiming;
use super::tls_setup::open_fat_header_probe_stream;

pub fn run_fat_header_attempt(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
) -> FatHeaderObservation {
    run_fat_header_attempt_with_key_log(target, transport, sni, host_header, None)
}

pub fn run_fat_header_attempt_with_key_log(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
    key_log: Option<&TlsKeyLogCallback>,
) -> FatHeaderObservation {
    let connect_target = match parse_connect_target(target) {
        Ok(target) => target,
        Err(err) => return FatHeaderObservation::connect_failed(err),
    };

    let tls_sni = if !sni.is_empty() { Some(sni) } else { None };
    let uses_tls = tls_sni.is_some();
    let probe_result = match open_fat_header_probe_stream(&connect_target, target.port, transport, tls_sni, key_log) {
        Ok(result) => result,
        Err(err) => {
            let status = if uses_tls { FatHeaderStatus::HandshakeFailed } else { FatHeaderStatus::ConnectFailed };
            return FatHeaderObservation::open_failed(status, err);
        }
    };

    run_padded_head_sequence(probe_result, target, host_header)
}

fn parse_connect_target(target: &TcpTarget) -> Result<TargetAddress, String> {
    target.ip.parse::<IpAddr>().map(TargetAddress::Ip).map_err(|err| err.to_string())
}

fn run_padded_head_sequence(
    mut probe_result: ProbeStreamResult,
    target: &TcpTarget,
    host_header: &str,
) -> FatHeaderObservation {
    let syn_ack_latency_ms = Some(probe_result.tcp_connect_ms);
    let stream = &mut probe_result.stream;
    // Socket timeouts bound one read; the watchdog bounds a peer that drips bytes within each.
    let observation = match probe_socket(stream).try_clone() {
        Ok(socket) => run_with_socket_watchdog(socket, None, || {
            padded_head_sequence(stream, target, host_header, syn_ack_latency_ms)
        }),
        Err(err) => FatHeaderObservation::open_failed(FatHeaderStatus::ConnectFailed, err.to_string()),
    };
    probe_result.stream.shutdown();
    observation
}

fn padded_head_sequence(
    stream: &mut ConnectionStream,
    target: &TcpTarget,
    host_header: &str,
    syn_ack_latency_ms: Option<u64>,
) -> FatHeaderObservation {
    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut counters = FatHeaderCounters::default();
    let mut timing = ProbeTiming::start();
    let host_header = effective_host_header(target, host_header);
    let host_header = host_header.as_str();

    for index in 0..requests {
        if arm_socket_timeouts(stream).is_err() {
            return deadline_exceeded(counters, syn_ack_latency_ms);
        }
        if let Err(err) = write_padded_head_request(stream, index, host_header) {
            if scan_deadline_passed() {
                return deadline_exceeded(counters, syn_ack_latency_ms);
            }
            counters.bytes_sent += padded_head_request_len(index, host_header);
            let status = classify_fat_io_error(err.kind(), counters.bytes_sent, counters.responses_seen);
            return FatHeaderObservation::io_failed(
                status,
                err.to_string(),
                syn_ack_latency_ms,
                Some(timing.elapsed_since_success_ms()),
                counters.bytes_sent,
                counters.responses_seen,
            );
        }
        counters.bytes_sent += padded_head_request_len(index, host_header);
        timing.mark_io_success();

        match read_response_headers(stream) {
            Ok(head) => {
                counters.responses_seen += 1;
                timing.mark_io_success();
                if !head.is_success_class() {
                    return inconclusive_response(counters, head, syn_ack_latency_ms);
                }
                if head.closes && index + 1 < requests {
                    if fat_threshold_reached(counters.bytes_sent) {
                        return FatHeaderObservation::success(
                            counters.bytes_sent,
                            counters.responses_seen,
                            syn_ack_latency_ms,
                        );
                    }
                    return inconclusive_response(counters, head, syn_ack_latency_ms);
                }
            }
            Err(err) => {
                if scan_deadline_passed() {
                    return deadline_exceeded(counters, syn_ack_latency_ms);
                }
                let status = classify_fat_io_error(err.kind(), counters.bytes_sent, counters.responses_seen);
                return FatHeaderObservation::io_failed(
                    status,
                    err.to_string(),
                    syn_ack_latency_ms,
                    Some(timing.elapsed_since_success_ms()),
                    counters.bytes_sent,
                    counters.responses_seen,
                );
            }
        }
    }

    FatHeaderObservation::success(counters.bytes_sent, counters.responses_seen, syn_ack_latency_ms)
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

fn scan_deadline_passed() -> bool {
    bounded_scan_io_timeout(IO_TIMEOUT).is_err()
}

/// The scan deadline ended the sequence, which says nothing about a middlebox cutoff. It is
/// inconclusive like a deadline during stream setup.
fn deadline_exceeded(counters: FatHeaderCounters, syn_ack_latency_ms: Option<u64>) -> FatHeaderObservation {
    FatHeaderObservation::io_failed(
        FatHeaderStatus::ConnectFailed,
        "scan_deadline_exceeded".to_string(),
        syn_ack_latency_ms,
        None,
        counters.bytes_sent,
        counters.responses_seen,
    )
}

/// A response that ends the sequence without saying anything about a middlebox cutoff: a
/// 4xx/5xx (which may be an injected block page), a head without a valid status line, or a
/// server-announced close before the threshold. It is inconclusive, which `ConnectFailed`
/// (`tcp_connect_failed`) reports. The error text carries the status and the reason into the
/// probe's `lastError` detail.
fn inconclusive_response(
    counters: FatHeaderCounters,
    head: ResponseHead,
    syn_ack_latency_ms: Option<u64>,
) -> FatHeaderObservation {
    let answer = head.status.map_or_else(
        || "answer without a valid HTTP status line".to_string(),
        |status| format!("HTTP {status} answer"),
    );
    let close = if head.closes { ", server closed the connection," } else { "" };
    FatHeaderObservation::io_failed(
        FatHeaderStatus::ConnectFailed,
        format!("{answer}{close} after response {} and {} bytes sent", counters.responses_seen, counters.bytes_sent),
        syn_ack_latency_ms,
        None,
        counters.bytes_sent,
        counters.responses_seen,
    )
}

#[derive(Default)]
struct FatHeaderCounters {
    bytes_sent: usize,
    responses_seen: usize,
}
