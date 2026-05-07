use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::{observe_outbound_payload, SessionState};

use crate::runtime::desync::{send_with_group, DesyncSendRequest, OutboundSendError};
use crate::runtime::state::RuntimeState;

pub(super) fn execute_first_write(
    upstream: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    original_request: &[u8],
    host: Option<&str>,
    session_state: &mut SessionState,
) -> Result<Option<&'static str>, OutboundSendError> {
    let progress = observe_outbound_payload(session_state, original_request);
    let send_outcome = send_with_group(
        upstream,
        state,
        DesyncSendRequest {
            group_index: route.group_index,
            group_override: None,
            payload: original_request,
            progress,
            host,
            target,
        },
    )?;
    tracing::debug!(
        target = %target,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "first outbound payload forwarded"
    );
    Ok(send_outcome.strategy_family)
}
