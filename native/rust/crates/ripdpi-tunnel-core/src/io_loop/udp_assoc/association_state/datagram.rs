#[cfg(test)]
use std::net::SocketAddr;
use std::sync::Arc;

use super::UdpDestination;
#[cfg(test)]
use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;

pub(in crate::io_loop::udp_assoc) struct OutboundDatagram {
    pub(in crate::io_loop::udp_assoc) destination: Arc<UdpDestination>,
    pub(in crate::io_loop::udp_assoc) payload: Vec<u8>,
    _queued_bytes: tokio::sync::OwnedSemaphorePermit,
}

impl OutboundDatagram {
    #[cfg(test)]
    pub(in crate::io_loop::udp_assoc) fn try_new(
        dest: TargetAddr,
        resolved_dest: SocketAddr,
        response_source: SocketAddr,
        payload: &[u8],
        memory_budget: &UdpMemoryBudget,
    ) -> Option<Self> {
        Self::try_new_for_destination(
            Arc::new(UdpDestination::new(dest, resolved_dest, response_source, None)),
            payload,
            memory_budget,
        )
    }

    pub(in crate::io_loop::udp_assoc) fn try_new_for_destination(
        destination: Arc<UdpDestination>,
        payload: &[u8],
        memory_budget: &UdpMemoryBudget,
    ) -> Option<Self> {
        let queued_bytes = memory_budget.try_reserve_queued_bytes(payload.len())?;
        Some(Self { destination, payload: payload.to_vec(), _queued_bytes: queued_bytes })
    }
}
