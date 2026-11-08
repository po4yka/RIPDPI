mod association_removal;
mod association_state;
mod destination_expiry;
mod event_handling;
mod eviction;
mod forwarding;
mod shutdown;
mod worker;

pub(super) use association_state::UdpAssociations;
pub(super) use destination_expiry::expire_udp_destinations;
pub(super) use event_handling::{UdpEvent, handle_udp_event};
pub(super) use eviction::{UDP_EVICTION_HEAP_CAPACITY, UdpEvictionEntry};
pub(super) use forwarding::{forward_udp_payload, retire_obsolete_udp_registration};
pub(super) use shutdown::{drain_udp_association_tasks, take_udp_association_tasks};

#[cfg(test)]
mod tests;

#[cfg(test)]
mod capacity_tests;

#[cfg(test)]
mod destination_lifetime_tests;

/// Release an observation only when it is not owned by a live association.
pub(crate) fn release_unowned_udp_attribution(
    associations: &UdpAssociations,
    src: std::net::SocketAddr,
    registration_id: ripdpi_flow_app_attribution::FlowRegistrationId,
) {
    if registration_id.request().local != src || !associations.owns_registration(&registration_id) {
        ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
    }
}

#[cfg(test)]
mod control_lifetime_tests;
