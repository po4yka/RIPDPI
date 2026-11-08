use std::num::NonZeroUsize;

mod activity;
mod association;
mod collection;
mod datagram;
mod destination;

#[cfg(test)]
pub(super) use activity::{activity_is_idle_at, touch_activity_at};
pub(super) use activity::{now_millis, touch_udp_activity, udp_association_is_idle};
pub(in crate::io_loop) use association::UdpAssociation;
pub(in crate::io_loop) use collection::{UdpAssociationKey, UdpAssociations};
pub(super) use datagram::OutboundDatagram;
pub(in crate::io_loop) use destination::UdpDestination;

pub(super) const UDP_OUTBOUND_QUEUE_CAPACITY: usize = 16;
pub(super) const UDP_DESTINATION_CAPACITY: usize = 256;

// At most 512 associations retain 32,768 tuple leases in total. Removing a lease
// only forces a fresh UID lookup; it never grants admission to an unknown flow.
pub(crate) const UDP_ATTRIBUTION_ID_CAPACITY: NonZeroUsize = match NonZeroUsize::new(64) {
    Some(capacity) => capacity,
    // Infallible: the literal capacity 64 is non-zero.
    None => panic!("UDP attribution capacity must be non-zero"),
};

#[cfg(test)]
mod scope_tests;
