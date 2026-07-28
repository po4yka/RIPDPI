use std::collections::VecDeque;

pub(in crate::io_loop) const PENDING_UID_UDP_CAPACITY: usize = 256;
pub(in crate::io_loop) const PENDING_UID_UDP_POOL_CAPACITY: usize = PENDING_UID_UDP_CAPACITY + 1;

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

pub(in crate::io_loop) struct PendingUidUdpPackets {
    queued: VecDeque<Vec<u8>>,
    free: Vec<Vec<u8>>,
    packet_capacity: usize,
}

impl PendingUidUdpPackets {
    pub(in crate::io_loop) fn new(packet_capacity: usize) -> Self {
        let free = (0..PENDING_UID_UDP_POOL_CAPACITY).map(|_| Vec::with_capacity(packet_capacity)).collect();
        Self { queued: VecDeque::with_capacity(PENDING_UID_UDP_CAPACITY), free, packet_capacity }
    }

    pub(in crate::io_loop) fn retain(&mut self, packet: &[u8]) -> PendingUidRetainOutcome {
        if packet.len() > self.packet_capacity {
            return PendingUidRetainOutcome::Rejected;
        }
        let evicted_oldest = self.queued.len() == PENDING_UID_UDP_CAPACITY;
        let buffer = if evicted_oldest { self.queued.pop_front() } else { self.free.pop() };
        let Some(mut buffer) = buffer else {
            return PendingUidRetainOutcome::Rejected;
        };
        buffer.clear();
        buffer.extend_from_slice(packet);
        self.queued.push_back(buffer);
        if evicted_oldest { PendingUidRetainOutcome::EvictedOldest } else { PendingUidRetainOutcome::Stored }
    }

    pub(in crate::io_loop) fn pop_front(&mut self) -> Option<Vec<u8>> {
        self.queued.pop_front()
    }

    pub(in crate::io_loop) fn recycle(&mut self, mut packet: Vec<u8>) {
        packet.clear();
        self.free.push(packet);
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
        self.queued.back().map(Vec::as_ptr)
    }
}
