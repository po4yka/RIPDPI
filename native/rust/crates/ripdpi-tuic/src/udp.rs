use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use bytes::BytesMut;
use tokio::sync::{Mutex, mpsc};
use tracing::debug;

use crate::client::ClientInner;
use crate::protocol::{PacketHeader, TuicAddress};

const CLEANUP_INTERVAL: Duration = Duration::from_secs(60);
const REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_REASSEMBLY_SLOTS: usize = 128;
const MAX_REASSEMBLED_PAYLOAD_SIZE: usize = 65_535;
const MAX_DESTINATIONS_PER_UDP_SESSION: usize = 256;

/// Maximum number of wrap-around retry attempts before declaring assoc-id space exhausted.
const MAX_ASSOC_ID_SCAN_ATTEMPTS: u16 = 1024;

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    destinations: Mutex<DestinationState>,
}

#[derive(Default)]
struct DestinationState {
    entries: HashMap<String, DestinationEntry>,
    access_sequence: u64,
}

struct DestinationEntry {
    assoc_id: u16,
    next_packet_id: u16,
    last_used: u64,
}

impl DestinationState {
    fn route_for_existing(&mut self, address: &str) -> Option<(u16, u16)> {
        self.access_sequence = self.access_sequence.saturating_add(1);
        let last_used = self.access_sequence;
        let entry = self.entries.get_mut(address)?;
        let packet_id = entry.next_packet_id;
        entry.next_packet_id = entry.next_packet_id.wrapping_add(1);
        entry.last_used = last_used;
        Some((entry.assoc_id, packet_id))
    }
}

impl UdpSession {
    pub(crate) fn new(client: Arc<ClientInner>) -> Self {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Self { client, incoming_rx, incoming_tx, destinations: Mutex::new(DestinationState::default()) }
    }

    pub async fn send_to(&self, address: &str, payload: &[u8]) -> io::Result<()> {
        let target = TuicAddress::from_authority(address)?;
        let (assoc_id, packet_id) = self.route_for(address).await?;
        let migrated = self.client.begin_quic_migration().await?;
        match send_udp_payload(&self.client, assoc_id, packet_id, &target, payload) {
            Ok(()) => {
                if migrated {
                    self.client.complete_quic_migration("path_validated_after_datagram_send").await;
                }
                Ok(())
            }
            Err(_error) if migrated => {
                let _ = self.client.rollback_quic_migration("datagram_send_failed_after_rebind").await;
                send_udp_payload(&self.client, assoc_id, packet_id, &target, payload)
            }
            Err(error) => Err(error),
        }
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "TUIC UDP session ended"))
            .map(|packet| (packet.address, packet.payload))
    }

    async fn route_for(&self, address: &str) -> io::Result<(u16, u16)> {
        let mut destinations = self.destinations.lock().await;
        if let Some(route) = destinations.route_for_existing(address) {
            return Ok(route);
        }
        let mut regs = self.client.registrations.lock().await;
        allocate_new_destination_route(
            &mut destinations,
            &self.client.next_assoc_id,
            &mut regs,
            self.incoming_tx.clone(),
            address,
        )
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::ResourceBusy,
                "TUIC UDP association-id space exhausted: too many concurrent sessions",
            )
        })
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }
}

impl Drop for UdpSession {
    fn drop(&mut self) {
        // NEVER panic in Drop.
        // Collect the assoc-ids this session registered. destinations is a per-session
        // Mutex, so try_lock() will always succeed in practice (no other holder once
        // drop() runs), but we treat the Err path gracefully.
        let ids: Vec<u16> = match self.destinations.try_lock() {
            Ok(state) => state.entries.values().map(|entry| entry.assoc_id).collect(),
            Err(_) => {
                // Could not acquire; entries will be cleaned up by
                // dispatch_incoming_datagrams's clear() when the connection closes.
                return;
            }
        };
        if ids.is_empty() {
            return;
        }

        // Fast path: registrations map is uncontended — remove synchronously.
        if let Ok(mut regs) = self.client.registrations.try_lock() {
            for id in &ids {
                regs.remove(id);
            }
            return;
        }

        // Slow path: map is locked (dispatch task holds it briefly while routing a packet).
        // Spawn a small cleanup task if a tokio runtime is available.
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let client = Arc::clone(&self.client);
            handle.spawn(async move {
                let mut regs = client.registrations.lock().await;
                for id in ids {
                    regs.remove(&id);
                }
            });
        }
        // If no runtime is available, entries die with the connection's clear() call.
    }
}

fn allocate_new_destination_route(
    destinations: &mut DestinationState,
    counter: &std::sync::atomic::AtomicU16,
    registrations: &mut HashMap<u16, mpsc::Sender<UdpPacket>>,
    sender: mpsc::Sender<UdpPacket>,
    address: &str,
) -> Option<(u16, u16)> {
    destinations.access_sequence = destinations.access_sequence.saturating_add(1);
    let last_used = destinations.access_sequence;
    if destinations.entries.len() >= MAX_DESTINATIONS_PER_UDP_SESSION
        && let Some(oldest_address) =
            destinations.entries.iter().min_by_key(|(_, entry)| entry.last_used).map(|(address, _)| address.clone())
        && let Some(evicted) = destinations.entries.remove(&oldest_address)
    {
        registrations.remove(&evicted.assoc_id);
    }

    let assoc_id = allocate_assoc_id(counter, registrations, sender)?;
    destinations.entries.insert(address.to_string(), DestinationEntry { assoc_id, next_packet_id: 1, last_used });
    Some((assoc_id, 0))
}

#[derive(Debug)]
pub(crate) struct UdpPacket {
    address: String,
    payload: Vec<u8>,
}

#[derive(Debug)]
struct PartialPacket {
    started_at: Instant,
    address: Option<TuicAddress>,
    fragments: Vec<Option<Vec<u8>>>,
    received: usize,
    buffered_bytes: usize,
}

enum ReassemblyResult {
    Pending,
    Complete(UdpPacket),
    Rejected,
}

// NOT cancel-safe: maintains mutable partials map across multiple await points.
// Cancellation mid-loop can leave reassembly state partially updated.
pub(crate) async fn dispatch_incoming_datagrams(client: Arc<ClientInner>) {
    let mut partials: HashMap<(u16, u16), PartialPacket> = HashMap::new();
    let mut last_cleanup = Instant::now();

    loop {
        let datagram = match client.connection.read_datagram().await {
            Ok(datagram) => datagram,
            Err(error) => {
                debug!(error = %error, "TUIC datagram dispatcher stopped");
                break;
            }
        };

        if last_cleanup.elapsed() >= CLEANUP_INTERVAL {
            partials.retain(|_, partial| partial.started_at.elapsed() < REASSEMBLY_TIMEOUT);
            while partials.len() > MAX_REASSEMBLY_SLOTS {
                if let Some(oldest_key) =
                    partials.iter().min_by_key(|(_, partial)| partial.started_at).map(|(key, _)| *key)
                {
                    partials.remove(&oldest_key);
                }
            }
            last_cleanup = Instant::now();
        }

        let (header, payload) = match PacketHeader::decode(&datagram) {
            Ok(decoded) => decoded,
            Err(error) => {
                debug!(error = %error, "Ignoring malformed TUIC datagram");
                continue;
            }
        };

        let sender = {
            let registrations = client.registrations.lock().await;
            registrations.get(&header.assoc_id).cloned()
        };
        let Some(sender) = sender else {
            continue;
        };

        if header.fragment_total <= 1 {
            let Ok(address) = header.address.to_authority() else {
                continue;
            };
            try_deliver(&sender, UdpPacket { address, payload: payload.to_vec() });
            continue;
        }

        let key = (header.assoc_id, header.packet_id);
        match reassemble_fragment(&mut partials, key, &header, payload) {
            ReassemblyResult::Complete(packet) => try_deliver(&sender, packet),
            ReassemblyResult::Pending | ReassemblyResult::Rejected => {}
        }
    }

    client.registrations.lock().await.clear();
}

fn try_deliver(sender: &mpsc::Sender<UdpPacket>, packet: UdpPacket) {
    if let Err(error) = sender.try_send(packet) {
        tracing::trace!(error = %error, "Dropping TUIC UDP packet for unavailable consumer");
    }
}

fn reassemble_fragment(
    partials: &mut HashMap<(u16, u16), PartialPacket>,
    key: (u16, u16),
    header: &PacketHeader,
    payload: &[u8],
) -> ReassemblyResult {
    if !partials.contains_key(&key) {
        while partials.len() >= MAX_REASSEMBLY_SLOTS {
            evict_oldest_partial(partials);
        }
        partials.insert(
            key,
            PartialPacket {
                started_at: Instant::now(),
                address: None,
                fragments: vec![None; usize::from(header.fragment_total)],
                received: 0,
                buffered_bytes: 0,
            },
        );
    }

    let Some(partial) = partials.get_mut(&key) else {
        return ReassemblyResult::Rejected;
    };
    let fragment_index = usize::from(header.fragment_id);
    if fragment_index >= partial.fragments.len() {
        return ReassemblyResult::Rejected;
    }
    if !matches!(header.address, TuicAddress::None) {
        partial.address = Some(header.address.clone());
    }
    if partial.fragments[fragment_index].is_none() {
        let Some(buffered_bytes) = partial.buffered_bytes.checked_add(payload.len()) else {
            partials.remove(&key);
            return ReassemblyResult::Rejected;
        };
        if buffered_bytes > MAX_REASSEMBLED_PAYLOAD_SIZE {
            partials.remove(&key);
            return ReassemblyResult::Rejected;
        }
        partial.fragments[fragment_index] = Some(payload.to_vec());
        partial.received += 1;
        partial.buffered_bytes = buffered_bytes;
    }
    if partial.received != partial.fragments.len() {
        return ReassemblyResult::Pending;
    }

    let Some(partial) = partials.remove(&key) else {
        return ReassemblyResult::Rejected;
    };
    let Some(address) = partial.address.and_then(|address| address.to_authority().ok()) else {
        return ReassemblyResult::Rejected;
    };
    let mut assembled = Vec::with_capacity(partial.buffered_bytes);
    for fragment in partial.fragments.iter().flatten() {
        assembled.extend_from_slice(fragment);
    }
    ReassemblyResult::Complete(UdpPacket { address, payload: assembled })
}

fn evict_oldest_partial(partials: &mut HashMap<(u16, u16), PartialPacket>) {
    if let Some(oldest_key) = partials.iter().min_by_key(|(_, partial)| partial.started_at).map(|(key, _)| *key) {
        partials.remove(&oldest_key);
    }
}

/// Allocate an assoc-id from the given atomic counter, checking `registrations`
/// for collisions. Returns the allocated id, or `None` if exhausted after
/// `MAX_ASSOC_ID_SCAN_ATTEMPTS` tries.
///
/// This is the single implementation used by both `assoc_id_for` in production
/// and the unit tests, so a change to the allocation algorithm is automatically
/// covered by the test suite.
pub(crate) fn allocate_assoc_id(
    counter: &std::sync::atomic::AtomicU16,
    registrations: &mut HashMap<u16, mpsc::Sender<UdpPacket>>,
    sender: mpsc::Sender<UdpPacket>,
) -> Option<u16> {
    use std::collections::hash_map::Entry;
    for _ in 0..MAX_ASSOC_ID_SCAN_ATTEMPTS {
        let candidate = counter.fetch_add(1, Ordering::SeqCst);
        if let Entry::Vacant(slot) = registrations.entry(candidate) {
            slot.insert(sender);
            return Some(candidate);
        }
    }
    None
}

fn send_udp_payload(
    client: &ClientInner,
    assoc_id: u16,
    packet_id: u16,
    address: &TuicAddress,
    payload: &[u8],
) -> io::Result<()> {
    let max_datagram_size = client
        .max_datagram_size
        .ok_or_else(|| io::Error::new(io::ErrorKind::Unsupported, "TUIC UDP is unavailable"))?;

    let first_header = PacketHeader {
        assoc_id,
        packet_id,
        fragment_total: 1,
        fragment_id: 0,
        payload_len: 0,
        address: address.clone(),
    };
    let next_header = PacketHeader { address: TuicAddress::None, ..first_header.clone() };
    let first_payload_capacity = max_datagram_size.saturating_sub(first_header.encoded_len());
    let next_payload_capacity = max_datagram_size.saturating_sub(next_header.encoded_len());

    if first_payload_capacity == 0 || next_payload_capacity == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "TUIC datagram header exceeds the maximum QUIC datagram size",
        ));
    }

    let fragment_total = if payload.len() <= first_payload_capacity {
        1
    } else {
        let remaining = payload.len() - first_payload_capacity;
        1 + remaining.div_ceil(next_payload_capacity)
    };
    if fragment_total > usize::from(u8::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "payload requires too many TUIC fragments"));
    }

    let mut written = 0usize;
    for fragment_id in 0..fragment_total {
        let address = if fragment_id == 0 { address.clone() } else { TuicAddress::None };
        let capacity = if fragment_id == 0 { first_payload_capacity } else { next_payload_capacity };
        let end = (written + capacity).min(payload.len());
        let chunk = &payload[written..end];
        let header = PacketHeader {
            assoc_id,
            packet_id,
            fragment_total: fragment_total as u8,
            fragment_id: fragment_id as u8,
            payload_len: chunk.len() as u16,
            address,
        };
        let mut frame = BytesMut::with_capacity(header.encoded_len() + chunk.len());
        header.encode(&mut frame);
        frame.extend_from_slice(chunk);
        client.connection.send_datagram(frame.freeze()).map_err(io::Error::other)?;
        written = end;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicU16;

    use tokio::sync::mpsc;

    use super::*;

    #[test]
    fn allocate_assoc_id_returns_free_id_when_space_available() {
        let counter = AtomicU16::new(0);
        let (tx, _rx) = mpsc::channel::<UdpPacket>(1);
        let mut regs: HashMap<u16, mpsc::Sender<UdpPacket>> = HashMap::new();
        let id = allocate_assoc_id(&counter, &mut regs, tx);
        assert_eq!(id, Some(0));
        assert!(regs.contains_key(&0));
    }

    #[test]
    fn allocate_assoc_id_skips_occupied_ids() {
        let counter = AtomicU16::new(0);
        let (tx0, _rx0) = mpsc::channel::<UdpPacket>(1);
        let (tx1, _rx1) = mpsc::channel::<UdpPacket>(1);
        // Pre-fill id 0 so the allocator must skip it.
        let mut regs: HashMap<u16, mpsc::Sender<UdpPacket>> = HashMap::new();
        regs.insert(0, tx0);
        // counter still starts at 0; first candidate (0) is occupied, second (1) is free.
        let id = allocate_assoc_id(&counter, &mut regs, tx1);
        assert_eq!(id, Some(1));
        assert!(regs.contains_key(&1));
        // Original entry for 0 must still be present and intact.
        assert!(regs.contains_key(&0));
    }

    #[test]
    fn allocate_assoc_id_returns_none_when_space_exhausted() {
        let counter = AtomicU16::new(0);
        let mut regs: HashMap<u16, mpsc::Sender<UdpPacket>> = HashMap::new();
        // Fill MAX_ASSOC_ID_SCAN_ATTEMPTS consecutive ids so every candidate hits.
        for id in 0..MAX_ASSOC_ID_SCAN_ATTEMPTS {
            let (tx, _rx) = mpsc::channel::<UdpPacket>(1);
            regs.insert(id, tx);
        }
        let (tx, _rx) = mpsc::channel::<UdpPacket>(1);
        let result = allocate_assoc_id(&counter, &mut regs, tx);
        assert!(result.is_none());
    }

    #[test]
    fn destination_state_is_bounded_and_evicts_least_recently_used_route() {
        let counter = AtomicU16::new(0);
        let (sender, _receiver) = mpsc::channel(1);
        let mut registrations = HashMap::new();
        let mut destinations = DestinationState::default();
        for index in 0..MAX_DESTINATIONS_PER_UDP_SESSION {
            let address = format!("host-{index}.example:53");
            allocate_new_destination_route(&mut destinations, &counter, &mut registrations, sender.clone(), &address)
                .expect("allocate destination route");
        }
        destinations.route_for_existing("host-0.example:53").expect("refresh oldest route");
        allocate_new_destination_route(&mut destinations, &counter, &mut registrations, sender, "new.example:53")
            .expect("allocate replacement route");

        assert_eq!(destinations.entries.len(), MAX_DESTINATIONS_PER_UDP_SESSION);
        assert_eq!(registrations.len(), MAX_DESTINATIONS_PER_UDP_SESSION);
        assert!(destinations.entries.contains_key("host-0.example:53"));
        assert!(!destinations.entries.contains_key("host-1.example:53"));
        assert!(destinations.entries.contains_key("new.example:53"));
    }

    #[test]
    fn existing_destination_reuses_id_and_advances_packet_id() {
        let counter = AtomicU16::new(7);
        let (sender, _receiver) = mpsc::channel(1);
        let mut registrations = HashMap::new();
        let mut destinations = DestinationState::default();

        let first = allocate_new_destination_route(
            &mut destinations,
            &counter,
            &mut registrations,
            sender.clone(),
            "example.com:53",
        )
        .expect("first route");
        let second = destinations.route_for_existing("example.com:53").expect("second route");

        assert_eq!(first, (7, 0));
        assert_eq!(second, (7, 1));
        assert_eq!(registrations.len(), 1);
    }

    #[test]
    fn saturated_session_does_not_block_other_udp_sessions() {
        let packet = |address: &str| UdpPacket { address: address.to_string(), payload: vec![1] };
        let (slow_tx, mut slow_rx) = mpsc::channel(1);
        let (fast_tx, mut fast_rx) = mpsc::channel(1);

        slow_tx.try_send(packet("slow.example:53")).expect("fill slow consumer queue");
        try_deliver(&slow_tx, packet("dropped.example:53"));
        try_deliver(&fast_tx, packet("fast.example:53"));

        assert_eq!(fast_rx.try_recv().expect("fast consumer receives packet").address, "fast.example:53");
        assert_eq!(slow_rx.try_recv().expect("queued slow packet remains").address, "slow.example:53");
        assert!(slow_rx.try_recv().is_err(), "overflow packet must be dropped");
    }

    #[test]
    fn reassembly_evicts_oldest_packet_at_slot_limit() {
        let mut partials = HashMap::new();
        let oldest_key = (0, 0);
        for index in 0..MAX_REASSEMBLY_SLOTS {
            partials.insert(
                (index as u16, 0),
                PartialPacket {
                    started_at: Instant::now() + Duration::from_millis(index as u64),
                    address: Some(TuicAddress::Domain("example.com".to_string(), 53)),
                    fragments: vec![Some(vec![1]), None],
                    received: 1,
                    buffered_bytes: 1,
                },
            );
        }
        let header = PacketHeader {
            assoc_id: u16::MAX,
            packet_id: 0,
            fragment_total: 2,
            fragment_id: 0,
            payload_len: 1,
            address: TuicAddress::Domain("new.example".to_string(), 53),
        };

        let result = reassemble_fragment(&mut partials, (header.assoc_id, header.packet_id), &header, &[1]);

        assert!(matches!(result, ReassemblyResult::Pending));
        assert_eq!(partials.len(), MAX_REASSEMBLY_SLOTS);
        assert!(!partials.contains_key(&oldest_key));
    }

    #[test]
    fn reassembly_rejects_payload_above_udp_limit() {
        let mut partials = HashMap::new();
        let key = (1, 1);
        let mut header = PacketHeader {
            assoc_id: key.0,
            packet_id: key.1,
            fragment_total: 2,
            fragment_id: 0,
            payload_len: 40_000,
            address: TuicAddress::Domain("example.com".to_string(), 53),
        };
        assert!(matches!(
            reassemble_fragment(&mut partials, key, &header, &vec![0; 40_000]),
            ReassemblyResult::Pending
        ));
        header.fragment_id = 1;
        header.payload_len = 30_000;
        header.address = TuicAddress::None;
        assert!(matches!(
            reassemble_fragment(&mut partials, key, &header, &vec![0; 30_000]),
            ReassemblyResult::Rejected
        ));
        assert!(!partials.contains_key(&key));
    }
}
