use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::session::SessionState;

use crate::runtime::desync::{send_with_group, OutboundSendError};
use crate::runtime::state::RuntimeState;
use ripdpi_runtime_decision_ports::policy::ConnectionRoute;

pub(super) fn execute_first_write(
    upstream: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    original_request: &[u8],
    host: Option<&str>,
    session_state: &mut SessionState,
) -> Result<Option<&'static str>, OutboundSendError> {
    let progress = session_state.observe_outbound(original_request);
    let group = state.config.groups[route.group_index].clone();
    let send_outcome =
        send_with_group(upstream, state, route.group_index, &group, original_request, progress, host, target)?;
    tracing::debug!(
        target = %target,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "first outbound payload forwarded"
    );
    Ok(send_outcome.strategy_family)
}
