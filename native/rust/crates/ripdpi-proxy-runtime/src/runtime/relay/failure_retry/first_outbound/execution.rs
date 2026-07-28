use std::net::{SocketAddr, TcpStream};

use crate::runtime::desync::{DesyncSendRequest, OutboundSendError};
use crate::runtime::relay::session::FirstOutboundSession;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

pub(super) fn execute_first_write(
    upstream: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    original_request: &[u8],
    host: Option<&str>,
    session_state: &mut FirstOutboundSession,
) -> Result<Option<&'static str>, OutboundSendError> {
    let progress = session_state.observe_first_outbound_payload(original_request);
    let send_result = state.send_tcp_desync_payload(
        upstream,
        DesyncSendRequest {
            group_index: route.group_index,
            group_override: None,
            payload: original_request,
            progress: progress.into_adapter(),
            host,
            target,
        },
    );
    state.note_upstream_application_send_result(&send_result);
    let send_outcome = send_result?;
    tracing::debug!(
        target = %target,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "first outbound payload forwarded"
    );
    Ok(send_outcome.strategy_family)
}
