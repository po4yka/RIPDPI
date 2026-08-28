use std::collections::VecDeque;
use std::time::{Duration, Instant};

use ripdpi_flow_app_attribution::FlowRegistrationId;

pub(in crate::io_loop) const PENDING_UID_CAPACITY: usize = 256;
pub(in crate::io_loop) const PENDING_UID_POOL_CAPACITY: usize = PENDING_UID_CAPACITY + 1;
const PENDING_UID_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) struct PendingUidPacket {
    pub(crate) bytes: Vec<u8>,
    // The TCP listener/session or UID cache owns the lifetime, not this queued packet.
    pub(crate) registration_id: FlowRegistrationId,
    pub(crate) captured_at: Instant,
}

impl PendingUidPacket {
    pub(crate) fn expired(&self) -> bool {
        self.captured_at.elapsed() >= PENDING_UID_TIMEOUT
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::io_loop) enum PendingUidRetainOutcome {
    Stored,
    EvictedOldest,
    Rejected,
}

impl PendingUidRetainOutcome {
    #[cfg(test)]
    pub(in crate::io_loop) fn is_stored(self) -> bool {
        matches!(self, Self::Stored | Self::EvictedOldest)
    }
}

pub(in crate::io_loop) struct PendingUidPackets {
    queued: VecDeque<PendingUidPacket>,
    free: Vec<Vec<u8>>,
    packet_capacity: usize,
}

impl PendingUidPackets {
    pub(in crate::io_loop) fn new(packet_capacity: usize) -> Self {
        let free = (0..PENDING_UID_POOL_CAPACITY).map(|_| Vec::with_capacity(packet_capacity)).collect();
        Self { queued: VecDeque::with_capacity(PENDING_UID_CAPACITY), free, packet_capacity }
    }

    pub(in crate::io_loop) fn retain(
        &mut self,
        packet: &[u8],
        registration_id: FlowRegistrationId,
        captured_at: Instant,
    ) -> PendingUidRetainOutcome {
        if packet.len() > self.packet_capacity {
            return PendingUidRetainOutcome::Rejected;
        }
        let evicted_oldest = self.queued.len() == PENDING_UID_CAPACITY;
        let buffer = if evicted_oldest { self.queued.pop_front().map(|packet| packet.bytes) } else { self.free.pop() };
        let Some(mut buffer) = buffer else {
            return PendingUidRetainOutcome::Rejected;
        };
        buffer.clear();
        buffer.extend_from_slice(packet);
        self.queued.push_back(PendingUidPacket { bytes: buffer, registration_id, captured_at });
        if evicted_oldest { PendingUidRetainOutcome::EvictedOldest } else { PendingUidRetainOutcome::Stored }
    }

    pub(in crate::io_loop) fn pop_front(&mut self) -> Option<PendingUidPacket> {
        self.queued.pop_front()
    }

    pub(in crate::io_loop) fn recycle(&mut self, mut packet: PendingUidPacket) {
        packet.bytes.clear();
        self.free.push(packet.bytes);
    }

    pub(in crate::io_loop) fn len(&self) -> usize {
        self.queued.len()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn is_empty(&self) -> bool {
        self.queued.is_empty()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn free_len(&self) -> usize {
        self.free.len()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn back_ptr(&self) -> Option<*const u8> {
        self.queued.back().map(|packet| packet.bytes.as_ptr())
    }
}
