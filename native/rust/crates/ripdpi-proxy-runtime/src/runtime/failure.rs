use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::{
    block_signal_from_failure, classify_first_response_closed_before_response,
    classify_first_response_partial_tls_timeout, classify_probe_connect_error, classify_probe_read_error,
    classify_probe_tls_response, classify_probe_write_error, classify_quic_probe, classify_relay_connection_freeze,
    classify_strategy_execution_failure, classify_transport_error, classify_warmup_closed_before_response,
    classify_warmup_first_response_error, classify_warmup_send_error, should_track_strategy_target, BlockSignal,
    BlockSignalObservation, ClassifiedFailure, FailureAction, FailureClass, FailureStage, ProbeResult,
};
use ripdpi_proxy_runtime_adapter::model::config::RuntimeTimeoutSettings;
use ripdpi_proxy_runtime_adapter::model::decision::{
    classify_response_failure, response_requires_dns_tampering_evidence, DnsTamperingEvidence,
};

pub(super) type RuntimeClassifiedFailure = ClassifiedFailure;
pub(super) type RuntimeBlockSignal = BlockSignal;
pub(super) type RuntimeBlockSignalObservation = BlockSignalObservation;
pub(super) type RuntimeFailureAction = FailureAction;
pub(super) type RuntimeFailureClass = FailureClass;
pub(super) type RuntimeFailureStage = FailureStage;
pub(super) type RuntimeDnsTamperingEvidence<'a> = DnsTamperingEvidence<'a>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum RuntimeProbeResult {
    Success,
    DpiFailure(&'static str),
    NetworkError(&'static str),
}

pub(super) fn runtime_response_requires_dns_tampering_evidence(request: &[u8], response: &[u8]) -> bool {
    response_requires_dns_tampering_evidence(request, response)
}

pub(super) fn runtime_classify_response_failure(
    request: &[u8],
    response: &[u8],
    dns_evidence: Option<RuntimeDnsTamperingEvidence<'_>>,
) -> Option<RuntimeClassifiedFailure> {
    classify_response_failure(request, response, dns_evidence)
}

pub(super) fn runtime_classify_quic_probe(outcome: &str, error: Option<&str>) -> Option<RuntimeClassifiedFailure> {
    classify_quic_probe(outcome, error)
}

pub(super) fn runtime_classify_transport_error(
    stage: RuntimeFailureStage,
    source: &io::Error,
) -> RuntimeClassifiedFailure {
    classify_transport_error(stage, source)
}

pub(super) fn runtime_classify_strategy_execution_failure(
    stage: RuntimeFailureStage,
    action: &'static str,
    source_kind: io::ErrorKind,
    source_errno: Option<i32>,
    description: String,
) -> Option<RuntimeClassifiedFailure> {
    classify_strategy_execution_failure(stage, action, source_kind, source_errno, description)
}

pub(super) fn runtime_classify_first_response_closed_before_response() -> RuntimeClassifiedFailure {
    classify_first_response_closed_before_response()
}

pub(super) fn runtime_classify_first_response_partial_tls_timeout() -> RuntimeClassifiedFailure {
    classify_first_response_partial_tls_timeout()
}

pub(super) fn runtime_classify_relay_connection_freeze(timeouts: RuntimeTimeoutSettings) -> RuntimeClassifiedFailure {
    classify_relay_connection_freeze(timeouts)
}

pub(super) fn runtime_classify_warmup_send_error(source: &io::Error) -> RuntimeClassifiedFailure {
    classify_warmup_send_error(source)
}

pub(super) fn runtime_classify_warmup_first_response_error(source: &io::Error) -> RuntimeClassifiedFailure {
    classify_warmup_first_response_error(source)
}

pub(super) fn runtime_classify_warmup_closed_before_response() -> RuntimeClassifiedFailure {
    classify_warmup_closed_before_response()
}

pub(super) fn runtime_classify_probe_connect_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_connect_error(source))
}

pub(super) fn runtime_classify_probe_write_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_write_error(source))
}

pub(super) fn runtime_classify_probe_read_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_read_error(source))
}

pub(super) fn runtime_classify_probe_tls_response(header: [u8; 5], handshake_type: Option<u8>) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_tls_response(header, handshake_type))
}

pub(super) fn runtime_should_track_strategy_target(target: SocketAddr) -> bool {
    should_track_strategy_target(target)
}

pub(super) fn runtime_block_signal_from_failure(
    failure: &RuntimeClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) -> Option<RuntimeBlockSignalObservation> {
    block_signal_from_failure(failure, tcp_total_retransmissions)
}

fn runtime_probe_result(result: ProbeResult) -> RuntimeProbeResult {
    match result {
        ProbeResult::Success => RuntimeProbeResult::Success,
        ProbeResult::DpiFailure(reason) => RuntimeProbeResult::DpiFailure(reason),
        ProbeResult::NetworkError(reason) => RuntimeProbeResult::NetworkError(reason),
    }
}
