use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use bytes::{BufMut, Bytes, BytesMut};
use tokio::sync::{Mutex, mpsc};

use crate::client::ClientInner;
use crate::error::{HysteriaError, Result};
use crate::varint::{decode_varint, encode_varint};

const CLEANUP_INTERVAL: Duration = Duration::from_secs(60);
const REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_REASSEMBLY_SLOTS: usize = 128;

/// Maximum number of wrap-around retry attempts before declaring session-id space exhausted.
/// u32 has 4 billion values so exhaustion is not a practical concern, but we still check
/// for wrap collisions to remain correct under adversarial or stress conditions.
const MAX_SESSION_ID_SCAN_ATTEMPTS: u32 = 1024;

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    session_ids: Mutex<HashMap<String, u32>>,
    packet_ids: Mutex<HashMap<u32, u16>>,
}

impl UdpSession {
    pub(crate) fn new(client: Arc<ClientInner>) -> Self {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Self {
            client,
            incoming_rx,
            incoming_tx,
            session_ids: Mutex::new(HashMap::new()),
            packet_ids: Mutex::new(HashMap::new()),
        }
    }

    pub async fn send_to(&self, address: &str, payload: &[u8]) -> Result<()> {
        let session_id = self.session_id_for(address).await?;
        let packet_id = self.next_packet_id(session_id).await;
        let migrated = self.client.begin_quic_migration().await?;
        match send_udp_payload(&self.client, session_id, packet_id, address, payload).await {
            Ok(()) => {
                if migrated {
                    self.client.complete_quic_migration("path_validated_after_datagram_send").await;
                }
                Ok(())
            }
            Err(_error) if migrated => {
                let _ = self.client.rollback_quic_migration("datagram_send_failed_after_rebind").await;
                send_udp_payload(&self.client, session_id, packet_id, address, payload).await
            }
            Err(error) => Err(error),
        }
    }

    pub async fn recv_from(&mut self) -> Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| HysteriaError::Io(io::Error::new(io::ErrorKind::BrokenPipe, "Hysteria UDP session ended")))
            .map(|packet| (packet.address, packet.payload))
    }

    // NOT cancel-safe: holds session_ids lock and registrations lock across await points;
    // dropping the future mid-way leaves the id allocated in session_ids but not registered,
    // or vice versa, resulting in an inconsistent state.
    async fn session_id_for(&self, address: &str) -> Result<u32> {
        let mut session_ids = self.session_ids.lock().await;
        if let Some(session_id) = session_ids.get(address).copied() {
            return Ok(session_id);
        }

        // Allocate via the shared helper (also exercised by unit tests).
        let mut regs = self.client.registrations.lock().await;
        let id = allocate_session_id(&self.client.next_session_id, &mut regs, self.incoming_tx.clone()).ok_or_else(
            || {
                HysteriaError::Io(io::Error::new(
                    io::ErrorKind::ResourceBusy,
                    "Hysteria2 UDP session-id space exhausted: too many concurrent sessions",
                ))
            },
        )?;
        session_ids.insert(address.to_string(), id);
        Ok(id)
    }

    async fn next_packet_id(&self, session_id: u32) -> u16 {
        let mut packet_ids = self.packet_ids.lock().await;
        let next = packet_ids.entry(session_id).or_insert(0);
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
        // Collect the session-ids this session registered. session_ids is a per-session
        // Mutex, so try_lock() will always succeed in practice (no other holder once
        // drop() runs), but we treat the Err path gracefully.
        let ids: Vec<u32> = match self.session_ids.try_lock() {
            Ok(map) => map.values().copied().collect(),
            Err(_) => {
                // Could not acquire; entries will be cleaned up by
                // dispatch_udp_datagrams's clear() when the connection closes.
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
    address: String,
    fragments: Vec<Option<Vec<u8>>>,
    received: usize,
}

// NOT cancel-safe: maintains mutable partials map across multiple await points.
// Cancellation mid-loop can leave reassembly state partially updated.
pub(crate) async fn dispatch_udp_datagrams(client: Arc<ClientInner>) {
    let mut partials: HashMap<(u32, u16), PartialPacket> = HashMap::new();
    let mut last_cleanup = Instant::now();

    loop {
        let datagram = match client.connection.read_datagram().await {
            Ok(datagram) => datagram,
            Err(error) => {
                tracing::debug!(error = %error, "Hysteria UDP dispatcher stopped");
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

        match parse_udp_datagram(&datagram) {
            Ok(ParsedDatagram { session_id, packet_id, fragment_id, fragment_count, address, payload }) => {
                let sender = {
                    let registrations = client.registrations.lock().await;
                    registrations.get(&session_id).cloned()
                };
                let Some(sender) = sender else {
                    continue;
                };

                if fragment_count <= 1 {
                    try_deliver(&sender, UdpPacket { address, payload });
                    continue;
                }

                let key = (session_id, packet_id);
                let partial = partials.entry(key).or_insert_with(|| PartialPacket {
                    started_at: Instant::now(),
                    address: address.clone(),
                    fragments: vec![None; usize::from(fragment_count)],
                    received: 0,
                });
                let index = usize::from(fragment_id);
                if index >= partial.fragments.len() {
                    continue;
                }
                if partial.fragments[index].is_none() {
                    partial.fragments[index] = Some(payload);
                    partial.received += 1;
                    partial.address = address;
                }

                if partial.received == partial.fragments.len() {
                    let mut assembled = Vec::new();
                    for fragment in partial.fragments.iter().flatten() {
                        assembled.extend_from_slice(fragment);
                    }
                    let packet = UdpPacket { address: partial.address.clone(), payload: assembled };
                    partials.remove(&key);
                    try_deliver(&sender, packet);
                }
            }
            Err(error) => {
                tracing::debug!(error = %error, "Ignoring malformed Hysteria UDP datagram");
            }
        }
    }

    client.registrations.lock().await.clear();
}

fn try_deliver(sender: &mpsc::Sender<UdpPacket>, packet: UdpPacket) {
    if let Err(error) = sender.try_send(packet) {
        tracing::trace!(error = %error, "Dropping Hysteria UDP packet for unavailable consumer");
    }
}

async fn send_udp_payload(
    client: &ClientInner,
    session_id: u32,
    packet_id: u16,
    address: &str,
    payload: &[u8],
) -> Result<()> {
    let max_datagram_size = client.max_datagram_size.ok_or(HysteriaError::UdpNotSupported)?;
    let address_len = encode_varint(address.len() as u64);
    let header_len = 4 + 2 + 1 + 1 + address_len.len() + address.len();
    if header_len >= max_datagram_size {
        return Err(HysteriaError::InvalidDatagram("Hysteria header exceeds max QUIC datagram size".to_string()));
    }

    if header_len + payload.len() <= max_datagram_size {
        let datagram = build_udp_datagram(session_id, packet_id, 0, 1, address, payload);
        client.connection.send_datagram(datagram)?;
        return Ok(());
    }

    let max_payload = max_datagram_size - header_len;
    let fragment_count = payload.len().div_ceil(max_payload);
    if fragment_count > usize::from(u8::MAX) {
        return Err(HysteriaError::InvalidDatagram("payload requires too many Hysteria UDP fragments".to_string()));
    }

    for (fragment_id, chunk) in payload.chunks(max_payload).enumerate() {
        let datagram =
            build_udp_datagram(session_id, packet_id, fragment_id as u8, fragment_count as u8, address, chunk);
        client.connection.send_datagram(datagram)?;
    }

    Ok(())
}

fn build_udp_datagram(
    session_id: u32,
    packet_id: u16,
    fragment_id: u8,
    fragment_count: u8,
    address: &str,
    payload: &[u8],
) -> Bytes {
    let mut datagram = BytesMut::with_capacity(8 + address.len() + payload.len() + 8);
    datagram.extend_from_slice(&session_id.to_be_bytes());
    datagram.extend_from_slice(&packet_id.to_be_bytes());
    datagram.put_u8(fragment_id);
    datagram.put_u8(fragment_count);
    datagram.extend_from_slice(&encode_varint(address.len() as u64));
    datagram.extend_from_slice(address.as_bytes());
    datagram.extend_from_slice(payload);
    datagram.freeze()
}

#[derive(Debug)]
struct ParsedDatagram {
    session_id: u32,
    packet_id: u16,
    fragment_id: u8,
    fragment_count: u8,
    address: String,
    payload: Vec<u8>,
}

fn parse_udp_datagram(datagram: &[u8]) -> Result<ParsedDatagram> {
    if datagram.len() < 8 {
        return Err(HysteriaError::InvalidDatagram("Hysteria UDP datagram is too short".to_string()));
    }

    let session_id = u32::from_be_bytes(datagram[0..4].try_into().expect("slice length"));
    let packet_id = u16::from_be_bytes(datagram[4..6].try_into().expect("slice length"));
    let fragment_id = datagram[6];
    let fragment_count = datagram[7];

    let (address_len, next_index) = decode_varint(&datagram[8..])?;
    // `address_len` is an untrusted QUIC varint (up to 0x3FFF_FFFF_FFFF_FFFF). Convert
    // through a checked path and use checked_add so the bounds check below holds on 32-bit
    // (armv7/i686) targets where `as usize` would truncate and the add could wrap.
    let address_len = usize::try_from(address_len)
        .map_err(|_| HysteriaError::InvalidDatagram("Hysteria UDP address length exceeds datagram".to_string()))?;
    let address_end = 8usize
        .checked_add(next_index)
        .and_then(|value| value.checked_add(address_len))
        .ok_or_else(|| HysteriaError::InvalidDatagram("Hysteria UDP address length exceeds datagram".to_string()))?;
    if datagram.len() < address_end {
        return Err(HysteriaError::InvalidDatagram("Hysteria UDP address length exceeds datagram".to_string()));
    }

    let address = String::from_utf8(datagram[8 + next_index..address_end].to_vec())
        .map_err(|_| HysteriaError::InvalidDatagram("Hysteria UDP address is not valid UTF-8".to_string()))?;
    let payload = datagram[address_end..].to_vec();

    Ok(ParsedDatagram { session_id, packet_id, fragment_id, fragment_count: fragment_count.max(1), address, payload })
}

/// Allocate a session-id from the given atomic counter, checking `registrations`
/// for collisions. Returns the allocated id, or `None` if every candidate within
/// `MAX_SESSION_ID_SCAN_ATTEMPTS` attempts is occupied.
///
/// This is the single implementation used by both `session_id_for` in production
/// and the unit tests, so a change to the allocation algorithm is automatically
/// covered by the test suite.
pub(crate) fn allocate_session_id(
    counter: &std::sync::atomic::AtomicU32,
    registrations: &mut HashMap<u32, mpsc::Sender<UdpPacket>>,
    sender: mpsc::Sender<UdpPacket>,
) -> Option<u32> {
    use std::collections::hash_map::Entry;
    for _ in 0..MAX_SESSION_ID_SCAN_ATTEMPTS {
        let candidate = counter.fetch_add(1, Ordering::SeqCst);
        if let Entry::Vacant(slot) = registrations.entry(candidate) {
            slot.insert(sender);
            return Some(candidate);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hysteria_udp_datagram_roundtrip() {
        let payload = b"udp payload";
        let datagram = build_udp_datagram(7, 3, 0, 1, "example.com:53", payload);
        let parsed = parse_udp_datagram(&datagram).expect("parse");
        assert_eq!(parsed.session_id, 7);
        assert_eq!(parsed.packet_id, 3);
        assert_eq!(parsed.fragment_id, 0);
        assert_eq!(parsed.fragment_count, 1);
        assert_eq!(parsed.address, "example.com:53");
        assert_eq!(parsed.payload, payload);
    }

    #[test]
    fn malformed_udp_datagram_returns_invalid_datagram() {
        let error = parse_udp_datagram(&[0, 1, 2]).expect_err("short datagram must fail");

        assert!(matches!(error, HysteriaError::InvalidDatagram(_)));
    }

    /// A datagram whose 8-byte varint encodes a huge address length must be
    /// rejected, not panic. On a 64-bit host this asserts the no-panic
    /// rejection and guards the `datagram.len() < address_end` bounds check
    /// (removing that check would panic the slice on 64-bit too). The narrower
    /// 32-bit teeth — `address_len as usize` truncation + add wrap on
    /// armv7/i686 — only manifest on a 32-bit runtime; CI cross-compiles but
    /// does not execute the 32-bit targets, so the checked conversion +
    /// `checked_add` are the load-bearing defense there.
    #[test]
    fn oversized_address_length_returns_invalid_datagram_not_panic() {
        // bytes 0..8: session_id (4) + packet_id (2) + fragment_id (1) + fragment_count (1).
        // bytes 8..16: 8-byte QUIC varint with tag 3 (0xC0 top bits); all-0xFF decodes
        // to 0x3FFF_FFFF_FFFF_FFFF — far larger than this 16-byte datagram.
        let datagram = [
            0u8, 0, 0, 0, // session_id
            0, 0, // packet_id
            0, // fragment_id
            1, // fragment_count
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // address_len varint
        ];

        let error = parse_udp_datagram(&datagram).expect_err("oversized address length must fail");

        assert!(matches!(error, HysteriaError::InvalidDatagram(_)));
    }

    /// Verify that `session_id_for` skips an already-occupied id and allocates
    /// a fresh one. This exercises the collision-detection path without a live
    /// QUIC connection by calling the inner logic through the free-function
    /// extracted for testability.
    #[test]
    fn session_id_allocator_skips_occupied_ids() {
        use std::sync::atomic::AtomicU32;
        let counter = AtomicU32::new(0);
        let (tx0, _rx0) = mpsc::channel::<UdpPacket>(1);
        let (tx1, _rx1) = mpsc::channel::<UdpPacket>(1);
        let mut regs: HashMap<u32, mpsc::Sender<UdpPacket>> = HashMap::new();
        // Pre-fill id 0.
        regs.insert(0, tx0);
        // Allocate: first candidate 0 is occupied, next (1) is free.
        let id = allocate_session_id(&counter, &mut regs, tx1);
        assert_eq!(id, Some(1));
        assert!(regs.contains_key(&0)); // original intact
        assert!(regs.contains_key(&1)); // new entry present
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
