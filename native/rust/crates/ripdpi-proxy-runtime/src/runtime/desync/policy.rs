use std::net::TcpStream;

use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::desync_platform::{
    send_tcp_desync_payload, DesyncSendRequest, OutboundSendError, OutboundSendOutcome, TcpDesyncExecutionContext,
};

pub(crate) fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    request: DesyncSendRequest<'_>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    send_tcp_desync_payload(
        writer,
        TcpDesyncExecutionContext {
            config: &state.config,
            runtime_context: state.runtime_context.as_ref(),
            telemetry: state.telemetry.as_deref(),
            adaptive_hints: state.adaptive_hints.as_ref(),
            ttl_unavailable: &state.ttl_unavailable,
            pcap_hook: state.pcap_hook.as_ref(),
        },
        request,
    )
}
