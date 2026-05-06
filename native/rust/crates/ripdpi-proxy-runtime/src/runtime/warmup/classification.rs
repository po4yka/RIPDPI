use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::{
    classify_transport_error, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};

use crate::runtime::routing::{classify_response_failure, emit_failure_classified};
use crate::runtime::state::RuntimeState;

pub(crate) fn classify_send_error(err: &io::Error) -> ClassifiedFailure {
    classify_transport_error(FailureStage::FirstWrite, err)
}

pub(crate) fn classify_first_response_error(err: &io::Error) -> ClassifiedFailure {
    classify_transport_error(FailureStage::FirstResponse, err)
}

pub(crate) fn classify_closed_before_response() -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::SilentDrop,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        "warmup: upstream closed before first response",
    )
}

pub(crate) fn classify_response(
    state: &RuntimeState,
    target: SocketAddr,
    payload: &[u8],
    response: &[u8],
    domain: &str,
) -> Option<ClassifiedFailure> {
    classify_response_failure(state, target, payload, response, Some(domain))
}

pub(crate) fn emit_classified_failure(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &ClassifiedFailure,
    domain: &str,
) {
    emit_failure_classified(state, target, failure, Some(domain));
}
