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
pub(super) use forwarding::{UdpForwardOutcome, forward_udp_payload};
pub(super) use shutdown::{drain_udp_association_tasks, take_udp_association_tasks};

#[cfg(test)]
mod tests;
