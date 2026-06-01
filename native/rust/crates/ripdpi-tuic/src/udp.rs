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
        let assoc_id = self.assoc_id_for(address).await;
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

    async fn assoc_id_for(&self, address: &str) -> u16 {
        let mut assoc_ids = self.assoc_ids.lock().await;
        if let Some(existing) = assoc_ids.get(address).copied() {
            return existing;
        }

        let assoc_id = self.client.next_assoc_id.fetch_add(1, Ordering::SeqCst);
        assoc_ids.insert(address.to_owned(), assoc_id);
        self.client.registrations.lock().await.insert(assoc_id, self.incoming_tx.clone());
        assoc_id
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
            let _ = sender.send(UdpPacket { address, payload: payload.to_vec() }).await;
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
            let _ = sender.send(UdpPacket { address, payload: assembled }).await;
        }
    }

    client.registrations.lock().await.clear();
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
