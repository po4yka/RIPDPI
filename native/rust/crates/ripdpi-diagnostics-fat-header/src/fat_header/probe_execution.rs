use std::net::IpAddr;

use crate::tls::ProbeStreamResult;
use crate::tls::TlsKeyLogCallback;
use crate::transport::{TargetAddress, TransportConfig};
use crate::types::TcpTarget;
use crate::util::FAT_HEADER_REQUESTS;

use super::classification::{classify_fat_error_message, classify_fat_io_error};
use super::request_io::{
    normalized_host_header, padded_head_request_len, read_response_headers, write_padded_head_request,
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
    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut counters = FatHeaderCounters::default();
    let mut timing = ProbeTiming::start();
    let host_header = normalized_host_header(host_header);

    for index in 0..requests {
        if let Err(err) = write_padded_head_request(&mut probe_result.stream, index, host_header) {
            counters.bytes_sent += padded_head_request_len(index, host_header);
            let status = classify_fat_io_error(err.kind(), counters.bytes_sent, counters.responses_seen);
            let observation = FatHeaderObservation::io_failed(
                status,
                err.to_string(),
                syn_ack_latency_ms,
                Some(timing.elapsed_since_success_ms()),
                counters.bytes_sent,
                counters.responses_seen,
            );
            probe_result.stream.shutdown();
            return observation;
        }
        counters.bytes_sent += padded_head_request_len(index, host_header);
        timing.mark_io_success();

        match read_response_headers(&mut probe_result.stream) {
            Ok(()) => {
                counters.responses_seen += 1;
                timing.mark_io_success();
            }
            Err(err) => {
                let status = classify_fat_error_message(&err, counters.bytes_sent, counters.responses_seen);
                let observation = FatHeaderObservation::io_failed(
                    status,
                    err,
                    syn_ack_latency_ms,
                    Some(timing.elapsed_since_success_ms()),
                    counters.bytes_sent,
                    counters.responses_seen,
                );
                probe_result.stream.shutdown();
                return observation;
            }
        }
    }

    probe_result.stream.shutdown();
    FatHeaderObservation::success(counters.bytes_sent, counters.responses_seen, syn_ack_latency_ms)
}

#[derive(Default)]
struct FatHeaderCounters {
    bytes_sent: usize,
    responses_seen: usize,
}
