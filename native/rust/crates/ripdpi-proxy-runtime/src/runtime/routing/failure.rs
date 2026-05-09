use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;

use super::super::state::RuntimeState;

mod advance;
mod cache;
mod feedback;
mod telemetry;

pub(in crate::runtime) use advance::advance_route_for_failure;
pub(in crate::runtime) use telemetry::emit_failure_classified;

pub(in crate::runtime) fn note_block_signal_for_failure(
    state: &RuntimeState,
    host: Option<&str>,
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) {
    state.note_block_signal_for_failure(host, failure, tcp_total_retransmissions);
}

pub(in crate::runtime) fn classify_response_failure(
    state: &RuntimeState,
    target: SocketAddr,
    request: &[u8],
    response: &[u8],
    host: Option<&str>,
) -> Option<ClassifiedFailure> {
    state.classify_response_failure(target, request, response, host)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synthetic_tunnel_dns_targets_do_not_participate_in_strategy_tracking() {
        assert!(!RuntimeState::should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 853))));
        assert!(!RuntimeState::should_track_strategy_target(SocketAddr::from(([198, 19, 42, 7], 853))));
        assert!(RuntimeState::should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 443))));
        assert!(RuntimeState::should_track_strategy_target(SocketAddr::from(([142, 251, 127, 84], 443))));
    }
}
