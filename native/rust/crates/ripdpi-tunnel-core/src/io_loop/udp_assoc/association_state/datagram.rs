use std::net::SocketAddr;

use crate::session::TargetAddr;
use crate::session::udp::UdpMemoryBudget;

pub(in crate::io_loop::udp_assoc) struct OutboundDatagram {
    pub(in crate::io_loop::udp_assoc) dest: TargetAddr,
    pub(in crate::io_loop::udp_assoc) resolved_dest: SocketAddr,
    pub(in crate::io_loop::udp_assoc) response_source: SocketAddr,
    pub(in crate::io_loop::udp_assoc) payload: Vec<u8>,
    _queued_bytes: tokio::sync::OwnedSemaphorePermit,
}

impl OutboundDatagram {
    pub(in crate::io_loop::udp_assoc) fn try_new(
        dest: TargetAddr,
        resolved_dest: SocketAddr,
        response_source: SocketAddr,
        payload: &[u8],
        memory_budget: &UdpMemoryBudget,
    ) -> Option<Self> {
        let queued_bytes = memory_budget.try_reserve_queued_bytes(payload.len())?;
        Some(Self { dest, resolved_dest, response_source, payload: payload.to_vec(), _queued_bytes: queued_bytes })
    }
}
