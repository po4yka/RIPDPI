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
const MAX_REASSEMBLED_PAYLOAD_SIZE: usize = 65_535;

/// Maximum number of wrap-around retry attempts before declaring session-id space exhausted.
/// u32 has 4 billion values so exhaustion is not a practical concern, but we still check
/// for wrap collisions to remain correct under adversarial or stress conditions.
const MAX_SESSION_ID_SCAN_ATTEMPTS: u32 = 1024;

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    route: Mutex<SessionRoute>,
}

/// One Hysteria session ID per local UDP session, as in the reference
/// client: each packet carries its own destination, so the server keeps one
/// outbound socket (one source port) for every peer of the association.
#[derive(Default)]
struct SessionRoute {
    session_id: Option<u32>,
    next_packet_id: u16,
}

impl UdpSession {
    pub(crate) fn new(client: Arc<ClientInner>) -> Self {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Self { client, incoming_rx, incoming_tx, route: Mutex::new(SessionRoute::default()) }
    }

    pub async fn send_to(&self, address: &str, payload: &[u8]) -> Result<()> {
        // The Android live path can receive the successful H3 auth response
        // just before the server's UDP session is ready to accept the first
        // QUIC datagram. Wait out that connection-scoped handoff window; on
        // other platforms `udp_ready_at` is the connection creation instant.
        tokio::time::sleep_until(self.client.udp_ready_at).await;
        let (session_id, packet_id) = self.next_route().await?;
        let migration = self.client.begin_quic_migration();
        match send_udp_payload(&self.client, session_id, packet_id, address, payload).await {
            Ok(()) => {
                if let Some(migration) = migration {
                    migration.complete("path_validated_after_datagram_send");
                }
                Ok(())
            }
            Err(error) => match migration {
                Some(migration) if error.is_path_failure() => {
                    migration.rollback("datagram_send_failed_after_rebind")?;
                    send_udp_payload(&self.client, session_id, packet_id, address, payload).await
                }
                // A payload the server cannot carry fails on any path; the
                // attempt rolls back on drop and the datagram is not resent.
                _ => Err(error),
            },
        }
    }

    /// Ends with `BrokenPipe` once the QUIC connection is gone. The session
    /// holds its own channel sender, so the channel alone never reports that.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: `mpsc::Receiver::recv` and `Connection::closed` lose no
    /// packet when dropped.
    pub async fn recv_from(&mut self) -> Result<(String, Vec<u8>)> {
        let packet = tokio::select! {
            // Deliver packets that arrived before the close first.
            biased;
            packet = self.incoming_rx.recv() => packet,
            _ = self.client.connection.closed() => self.incoming_rx.try_recv().ok(),
        };
        packet
            .ok_or_else(|| HysteriaError::Io(io::Error::new(io::ErrorKind::BrokenPipe, "Hysteria UDP session ended")))
            .map(|packet| (packet.address, packet.payload))
    }

    /// Registers the session ID on first use and returns it with the next
    /// packet ID.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: both locks are Tokio mutexes, and the registration is
    /// stored in the same poll that allocates it.
    async fn next_route(&self) -> Result<(u32, u16)> {
        let mut route = self.route.lock().await;
        let session_id = match route.session_id {
            Some(session_id) => session_id,
            None => {
                let mut regs = self.client.registrations.lock().await;
                let session_id = allocate_session_id(&self.client.next_session_id, &mut regs, self.incoming_tx.clone())
                    .ok_or_else(|| {
                        HysteriaError::Io(io::Error::new(
                            io::ErrorKind::ResourceBusy,
                            "Hysteria2 UDP session-id space exhausted: too many concurrent sessions",
                        ))
                    })?;
                route.session_id = Some(session_id);
                session_id
            }
        };
        let packet_id = route.next_packet_id;
        route.next_packet_id = packet_id.wrapping_add(1);
        Ok((session_id, packet_id))
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }
}

impl Drop for UdpSession {
    fn drop(&mut self) {
        // NEVER panic in Drop.
        // The route mutex is per session, so try_lock() succeeds once drop()
        // runs; on the Err path the dispatcher's clear() removes the entry
        // when the connection closes.
        let Some(session_id) = self.route.try_lock().ok().and_then(|route| route.session_id) else {
            return;
        };

        // Fast path: registrations map is uncontended — remove synchronously.
        if let Ok(mut regs) = self.client.registrations.try_lock() {
            regs.remove(&session_id);
            return;
        }

        // Slow path: map is locked (dispatch task holds it briefly while routing a packet).
        // Spawn a small cleanup task if a tokio runtime is available.
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let client = Arc::clone(&self.client);
            handle.spawn(async move {
                client.registrations.lock().await.remove(&session_id);
            });
        }
        // If no runtime is available, the entry dies with the connection's clear() call.
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
    fragments: Vec<Option<Vec<u8>>>,
    received: usize,
    buffered_bytes: usize,
}

/// Session ID, packet ID and the reply's address: peers of one association share
/// the session ID, and their server-chosen packet IDs can collide.
type ReassemblyKey = (u32, u16, String);

enum ReassemblyResult {
    Pending,
    Complete(UdpPacket),
    Rejected,
}

/// # Cancel safety
///
/// NOT cancel-safe: maintains mutable partials map across multiple await points.
/// Cancellation mid-loop can leave reassembly state partially updated.
pub(crate) async fn dispatch_udp_datagrams(client: Arc<ClientInner>) {
    let mut partials: HashMap<ReassemblyKey, PartialPacket> = HashMap::new();
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
                evict_oldest_partial(&mut partials);
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
                match reassemble_fragment(&mut partials, key, fragment_id, fragment_count, address, payload) {
                    ReassemblyResult::Complete(packet) => try_deliver(&sender, packet),
                    ReassemblyResult::Pending | ReassemblyResult::Rejected => {}
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

fn reassemble_fragment(
    partials: &mut HashMap<ReassemblyKey, PartialPacket>,
    (session_id, packet_id): (u32, u16),
    fragment_id: u8,
    fragment_count: u8,
    address: String,
    payload: Vec<u8>,
) -> ReassemblyResult {
    let key = (session_id, packet_id, address);
    // A fragment count that differs from the stored one belongs to another packet: start again.
    if partials.get(&key).is_some_and(|partial| partial.fragments.len() != usize::from(fragment_count)) {
        partials.remove(&key);
    }
    if !partials.contains_key(&key) {
        while partials.len() >= MAX_REASSEMBLY_SLOTS {
            evict_oldest_partial(partials);
        }
        partials.insert(
            key.clone(),
            PartialPacket {
                started_at: Instant::now(),
                fragments: vec![None; usize::from(fragment_count)],
                received: 0,
                buffered_bytes: 0,
            },
        );
    }

    let Some(partial) = partials.get_mut(&key) else {
        return ReassemblyResult::Rejected;
    };
    let index = usize::from(fragment_id);
    if index >= partial.fragments.len() {
        return ReassemblyResult::Rejected;
    }
    if partial.fragments[index].is_none() {
        let Some(buffered_bytes) = partial.buffered_bytes.checked_add(payload.len()) else {
            partials.remove(&key);
            return ReassemblyResult::Rejected;
        };
        if buffered_bytes > MAX_REASSEMBLED_PAYLOAD_SIZE {
            partials.remove(&key);
            return ReassemblyResult::Rejected;
        }
        partial.fragments[index] = Some(payload);
        partial.received += 1;
        partial.buffered_bytes = buffered_bytes;
    }
    if partial.received != partial.fragments.len() {
        return ReassemblyResult::Pending;
    }

    let Some((key, partial)) = partials.remove_entry(&key) else {
        return ReassemblyResult::Rejected;
    };
    let mut assembled = Vec::with_capacity(partial.buffered_bytes);
    for fragment in partial.fragments.iter().flatten() {
        assembled.extend_from_slice(fragment);
    }
    ReassemblyResult::Complete(UdpPacket { address: key.2, payload: assembled })
}

fn evict_oldest_partial(partials: &mut HashMap<ReassemblyKey, PartialPacket>) {
    if let Some(oldest_key) = partials.iter().min_by_key(|(_, partial)| partial.started_at).map(|(key, _)| key.clone())
    {
        partials.remove(&oldest_key);
    }
}

async fn send_udp_payload(
    client: &ClientInner,
    session_id: u32,
    packet_id: u16,
    address: &str,
    payload: &[u8],
) -> Result<()> {
    let max_datagram_size = client.connection.max_datagram_size().ok_or(HysteriaError::UdpNotSupported)?;
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
    use crate::test_server::{ServerEvent, ServerOptions, TestServer};

    const DEADLINE: Duration = Duration::from_secs(5);

    /// The reference client uses one session per local socket and addresses
    /// each packet, so the server keeps one outbound socket (one source port)
    /// for every peer of the association.
    #[tokio::test]
    async fn one_udp_session_uses_one_session_id_for_every_destination() {
        let mut server = TestServer::start(ServerOptions::default()).expect("start test server");
        let client = crate::connect(&server.client_config()).await.expect("connect");
        let mut session = client.udp_session().await.expect("UDP session");

        let mut seen = Vec::new();
        for destination in ["192.0.2.1:3478", "198.51.100.2:3478"] {
            session.send_to(destination, b"stun").await.expect("send datagram");
            let (address, _) = tokio::time::timeout(DEADLINE, session.recv_from()).await.expect("echo").expect("echo");
            assert_eq!(address, destination, "replies keep their peer address");
            seen.push(server.events.try_recv().expect("server saw the datagram"));
        }

        assert_eq!(seen[0], seen[1], "both peers must share one Hysteria UDP session");
    }

    #[tokio::test]
    async fn recv_from_reports_session_end_after_the_connection_dies() {
        let mut server = TestServer::start(ServerOptions::default()).expect("start test server");
        let client = crate::connect(&server.client_config()).await.expect("connect");
        let mut session = client.udp_session().await.expect("UDP session");
        session.send_to("192.0.2.1:53", b"ping").await.expect("send datagram");
        let (_, echoed) = tokio::time::timeout(DEADLINE, session.recv_from()).await.expect("echo").expect("echo");
        assert_eq!(echoed, b"ping");
        assert!(matches!(server.events.try_recv(), Ok(ServerEvent::Datagram { .. })));

        server.close();
        let ended = tokio::time::timeout(DEADLINE, session.recv_from())
            .await
            .expect("recv_from must return once the QUIC connection is gone");

        assert!(ended.is_err(), "a dead connection must end the session, got {ended:?}");
    }

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

    #[test]
    fn reassembly_evicts_oldest_packet_at_slot_limit() {
        let mut partials = HashMap::new();
        let oldest_key = (0, 0, "example.com:53".to_string());
        for index in 0..MAX_REASSEMBLY_SLOTS {
            partials.insert(
                (index as u32, 0, "example.com:53".to_string()),
                PartialPacket {
                    started_at: Instant::now() + Duration::from_millis(index as u64),
                    fragments: vec![Some(vec![1]), None],
                    received: 1,
                    buffered_bytes: 1,
                },
            );
        }

        let result = reassemble_fragment(&mut partials, (u32::MAX, 0), 0, 2, "new.example:53".to_string(), vec![1]);

        assert!(matches!(result, ReassemblyResult::Pending));
        assert_eq!(partials.len(), MAX_REASSEMBLY_SLOTS);
        assert!(!partials.contains_key(&oldest_key));
    }

    #[test]
    fn reassembly_rejects_payload_above_udp_limit() {
        let mut partials = HashMap::new();
        let key = (1, 1);
        assert!(matches!(
            reassemble_fragment(&mut partials, key, 0, 2, "example.com:53".to_string(), vec![0; 40_000]),
            ReassemblyResult::Pending
        ));
        assert!(matches!(
            reassemble_fragment(&mut partials, key, 1, 2, "example.com:53".to_string(), vec![0; 30_000]),
            ReassemblyResult::Rejected
        ));
        assert!(partials.is_empty());
    }

    fn complete_payload(result: ReassemblyResult) -> (String, Vec<u8>) {
        match result {
            ReassemblyResult::Complete(packet) => (packet.address, packet.payload),
            ReassemblyResult::Pending => panic!("expected a complete packet, got Pending"),
            ReassemblyResult::Rejected => panic!("expected a complete packet, got Rejected"),
        }
    }

    #[test]
    fn reassembly_keeps_two_peers_with_the_same_packet_id_apart() {
        let mut partials = HashMap::new();
        let key = (7, 42);
        let peer_a = "a.example:3478".to_string();
        let peer_b = "b.example:3478".to_string();

        assert!(matches!(
            reassemble_fragment(&mut partials, key, 0, 2, peer_a.clone(), b"A0".to_vec()),
            ReassemblyResult::Pending
        ));
        assert!(matches!(
            reassemble_fragment(&mut partials, key, 0, 2, peer_b.clone(), b"B0".to_vec()),
            ReassemblyResult::Pending
        ));
        assert_eq!(
            complete_payload(reassemble_fragment(&mut partials, key, 1, 2, peer_a.clone(), b"A1".to_vec())),
            (peer_a, b"A0A1".to_vec())
        );
        assert_eq!(
            complete_payload(reassemble_fragment(&mut partials, key, 1, 2, peer_b.clone(), b"B1".to_vec())),
            (peer_b, b"B0B1".to_vec())
        );
        assert!(partials.is_empty());
    }

    #[test]
    fn reassembly_does_not_mix_fragments_with_a_different_fragment_count() {
        let mut partials = HashMap::new();
        let key = (7, 42);
        let peer = "a.example:3478".to_string();

        assert!(matches!(
            reassemble_fragment(&mut partials, key, 0, 2, peer.clone(), b"old0".to_vec()),
            ReassemblyResult::Pending
        ));
        assert!(matches!(
            reassemble_fragment(&mut partials, key, 1, 3, peer.clone(), b"new1".to_vec()),
            ReassemblyResult::Pending
        ));
        assert!(matches!(
            reassemble_fragment(&mut partials, key, 0, 3, peer.clone(), b"new0".to_vec()),
            ReassemblyResult::Pending
        ));
        assert_eq!(
            complete_payload(reassemble_fragment(&mut partials, key, 2, 3, peer.clone(), b"new2".to_vec())),
            (peer, b"new0new1new2".to_vec())
        );
    }
}
