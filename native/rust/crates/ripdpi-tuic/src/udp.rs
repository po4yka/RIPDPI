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

/// Maximum number of wrap-around retry attempts before declaring assoc-id space exhausted.
const MAX_ASSOC_ID_SCAN_ATTEMPTS: u16 = 1024;

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    assoc_ids: Mutex<HashMap<String, u16>>,
    packet_ids: Mutex<HashMap<u16, u16>>,
}

impl UdpSession {
    pub(crate) fn new(client: Arc<ClientInner>) -> Self {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Self {
            client,
            incoming_rx,
            incoming_tx,
            assoc_ids: Mutex::new(HashMap::new()),
            packet_ids: Mutex::new(HashMap::new()),
        }
    }

    pub async fn send_to(&self, address: &str, payload: &[u8]) -> io::Result<()> {
        let target = TuicAddress::from_authority(address)?;
        let assoc_id = self.assoc_id_for(address).await?;
        let packet_id = self.next_packet_id(assoc_id).await;
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

    // NOT cancel-safe: holds assoc_ids lock and registrations lock across await points;
    // dropping the future mid-way leaves the id allocated in assoc_ids but not registered,
    // or vice versa, resulting in an inconsistent state.
    async fn assoc_id_for(&self, address: &str) -> io::Result<u16> {
        let mut assoc_ids = self.assoc_ids.lock().await;
        if let Some(existing) = assoc_ids.get(address).copied() {
            return Ok(existing);
        }

        // Scan for a free id via the shared helper (also exercised by unit tests).
        let mut regs = self.client.registrations.lock().await;
        let id =
            allocate_assoc_id(&self.client.next_assoc_id, &mut regs, self.incoming_tx.clone()).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::ResourceBusy,
                    "TUIC UDP association-id space exhausted: too many concurrent sessions",
                )
            })?;
        assoc_ids.insert(address.to_owned(), id);
        Ok(id)
    }

    async fn next_packet_id(&self, assoc_id: u16) -> u16 {
        let mut packet_ids = self.packet_ids.lock().await;
        let next = packet_ids.entry(assoc_id).or_insert(0);
        let packet_id = *next;
        *next = next.wrapping_add(1);
        packet_id
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }
}

impl Drop for UdpSession {
    fn drop(&mut self) {
        // NEVER panic in Drop.
        // Collect the assoc-ids this session registered. assoc_ids is a per-session
        // Mutex, so try_lock() will always succeed in practice (no other holder once
        // drop() runs), but we treat the Err path gracefully.
        let ids: Vec<u16> = match self.assoc_ids.try_lock() {
            Ok(map) => map.values().copied().collect(),
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
        let partial = partials.entry(key).or_insert_with(|| PartialPacket {
            started_at: Instant::now(),
            address: None,
            fragments: vec![None; usize::from(header.fragment_total)],
            received: 0,
        });
        let fragment_index = usize::from(header.fragment_id);
        if fragment_index >= partial.fragments.len() {
            continue;
        }
        if !matches!(header.address, TuicAddress::None) {
            partial.address = Some(header.address.clone());
        }
        if partial.fragments[fragment_index].is_none() {
            partial.fragments[fragment_index] = Some(payload.to_vec());
            partial.received += 1;
        }

        if partial.received == partial.fragments.len() {
            let Some(address) = partial.address.clone() else {
                partials.remove(&key);
                continue;
            };
            let mut assembled = Vec::new();
            for fragment in partial.fragments.iter().flatten() {
                assembled.extend_from_slice(fragment);
            }
            partials.remove(&key);
            let Ok(address) = address.to_authority() else {
                continue;
            };
            try_deliver(&sender, UdpPacket { address, payload: assembled });
        }
    }

    client.registrations.lock().await.clear();
}

fn try_deliver(sender: &mpsc::Sender<UdpPacket>, packet: UdpPacket) {
    if let Err(error) = sender.try_send(packet) {
        tracing::trace!(error = %error, "Dropping TUIC UDP packet for unavailable consumer");
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
}
