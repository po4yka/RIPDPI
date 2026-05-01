mod association_state;
mod event_handling;
mod eviction;
mod forwarding;
mod shutdown;
mod worker;

pub(super) use association_state::UdpAssociation;
pub(super) use event_handling::{handle_udp_event, UdpEvent};
pub(super) use eviction::{UdpEvictionEntry, DEFAULT_MAX_UDP_ASSOCIATIONS};
pub(super) use forwarding::forward_udp_payload;
pub(super) use shutdown::shutdown_udp_associations;

#[cfg(test)]
mod tests;
