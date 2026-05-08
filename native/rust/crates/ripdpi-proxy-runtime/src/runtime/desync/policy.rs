use std::net::TcpStream;

use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::desync_platform::{
    send_tcp_desync_payload, DesyncSendRequest, OutboundSendError, OutboundSendOutcome,
};

pub(crate) fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    request: DesyncSendRequest<'_>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    send_tcp_desync_payload(writer, state.tcp_desync_execution_context(), request)
}
