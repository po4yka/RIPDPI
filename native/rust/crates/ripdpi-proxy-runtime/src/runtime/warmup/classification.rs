use std::io;
use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::{
    classify_warmup_closed_before_response as adapter_classify_closed_before_response,
    classify_warmup_first_response_error as adapter_classify_first_response_error,
    classify_warmup_send_error as adapter_classify_send_error, ClassifiedFailure,
};

use crate::runtime::routing::{classify_response_failure, emit_failure_classified};
use crate::runtime::state::RuntimeState;

pub(crate) fn classify_send_error(err: &io::Error) -> ClassifiedFailure {
    adapter_classify_send_error(err)
}

pub(crate) fn classify_first_response_error(err: &io::Error) -> ClassifiedFailure {
    adapter_classify_first_response_error(err)
}

pub(crate) fn classify_closed_before_response() -> ClassifiedFailure {
    adapter_classify_closed_before_response()
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
