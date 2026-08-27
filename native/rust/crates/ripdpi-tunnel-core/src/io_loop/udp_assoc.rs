mod association_removal;
mod association_state;
mod event_handling;
mod eviction;
mod forwarding;
mod shutdown;
mod worker;

pub(super) use association_state::UdpAssociation;
pub(super) use event_handling::{UdpEvent, handle_udp_event};
pub(super) use eviction::{UDP_EVICTION_HEAP_CAPACITY, UdpEvictionEntry};
pub(super) use forwarding::forward_udp_payload;
pub(super) use shutdown::{drain_udp_association_tasks, take_udp_association_tasks};

#[cfg(test)]
mod tests;

/// Release an observation only when it is not owned by a live association.
pub(crate) fn release_unowned_udp_attribution(
    associations: &std::collections::HashMap<std::net::SocketAddr, UdpAssociation>,
    src: std::net::SocketAddr,
    token: ripdpi_flow_app_attribution::FlowAttributionToken,
) {
    if !associations
        .get(&src)
        .is_some_and(|association| association.attribution_tokens.peek(&token.request()) == Some(&token))
    {
        ripdpi_flow_app_attribution::evict_flow(token);
    }
}
