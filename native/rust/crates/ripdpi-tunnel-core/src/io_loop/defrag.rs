//! Bounded reassembly of fragmented IPv4 and IPv6 UDP datagrams in front of packet routing.
//!
//! The UDP path proxies whole datagrams, so a datagram larger than the TUN MTU that the app's
//! kernel fragments was lost. This module rebuilds such a datagram into one unfragmented IP packet
//! that the normal routing then handles. Other protocols keep their path: TCP segments fit the MSS,
//! and ICMP fragments must still meet the UID policy one by one.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use etherparse::defrag::{IpDefragBuf, IpFragId, IpFragVersionSpecId};
use etherparse::{
    IpFragOffset, IpNumber, Ipv4Header, Ipv6ExtensionSlice, Ipv6FlowLabel, Ipv6Header, NetSlice, SlicedPacket,
};

/// Datagrams in reassembly at one time. Each one holds at most 64 KiB.
const MAX_REASSEMBLIES: usize = 32;
/// A datagram whose fragments stop arriving for this long is dropped.
const REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(5);

pub(in crate::io_loop) enum Reassembly {
    /// The packet is not a fragment; route it as it is.
    NotFragment,
    /// The fragment is kept, or dropped because it is malformed or over the limits.
    Held,
    /// The last missing fragment arrived: the whole datagram as one unfragmented IP packet.
    Complete(Vec<u8>),
}

struct Pending {
    buf: IpDefragBuf,
    hop_limit: u8,
    last_seen: Instant,
}

#[derive(Default)]
pub(in crate::io_loop) struct IpDefrag {
    pending: HashMap<IpFragId, Pending>,
}

struct Fragment<'a> {
    id: IpFragId,
    offset: IpFragOffset,
    more: bool,
    payload: &'a [u8],
    hop_limit: u8,
}

impl IpDefrag {
    pub(in crate::io_loop) fn accept(&mut self, packet: &[u8], now: Instant) -> Reassembly {
        let Some(fragment) = parse_fragment(packet) else {
            return Reassembly::NotFragment;
        };
        self.pending.retain(|_, pending| now.duration_since(pending.last_seen) <= REASSEMBLY_TIMEOUT);
        if !self.pending.contains_key(&fragment.id) && self.pending.len() >= MAX_REASSEMBLIES {
            return Reassembly::Held;
        }
        let pending = self.pending.entry(fragment.id.clone()).or_insert_with(|| Pending {
            buf: IpDefragBuf::new(fragment.id.payload_ip_number, Vec::new(), Vec::new()),
            hop_limit: fragment.hop_limit,
            last_seen: now,
        });
        pending.last_seen = now;
        let added = pending.buf.add(fragment.offset, fragment.more, fragment.payload);
        if added.is_ok() && !pending.buf.is_complete() {
            return Reassembly::Held;
        }
        // Complete or malformed: either way the datagram leaves the pool.
        let Some(pending) = self.pending.remove(&fragment.id) else {
            return Reassembly::Held;
        };
        if added.is_err() {
            return Reassembly::Held;
        }
        let (payload, _) = pending.buf.take_bufs();
        rebuild_packet(&fragment.id, pending.hop_limit, &payload).map_or(Reassembly::Held, Reassembly::Complete)
    }
}

fn parse_fragment(packet: &[u8]) -> Option<Fragment<'_>> {
    parse_ip_fragment(packet).filter(|fragment| fragment.id.payload_ip_number == IpNumber::UDP)
}

fn parse_ip_fragment(packet: &[u8]) -> Option<Fragment<'_>> {
    let sliced = SlicedPacket::from_ip(packet).ok()?;
    match sliced.net.as_ref()? {
        NetSlice::Ipv4(ipv4) => {
            let header = ipv4.header();
            header.is_fragmenting_payload().then(|| Fragment {
                id: fragment_id(
                    &sliced,
                    IpFragVersionSpecId::Ipv4 {
                        source: header.source(),
                        destination: header.destination(),
                        identification: header.identification(),
                    },
                    ipv4.payload().ip_number,
                ),
                offset: header.fragments_offset(),
                more: header.more_fragments(),
                payload: ipv4.payload().payload,
                hop_limit: header.ttl(),
            })
        }
        NetSlice::Ipv6(ipv6) => {
            let fragment = ipv6.extensions().clone().into_iter().find_map(|ext| match ext {
                Ipv6ExtensionSlice::Fragment(fragment) => Some(fragment),
                _ => None,
            })?;
            let header = fragment.is_fragmenting_payload().then(|| fragment.to_header())?;
            Some(Fragment {
                id: fragment_id(
                    &sliced,
                    IpFragVersionSpecId::Ipv6 {
                        source: ipv6.header().source(),
                        destination: ipv6.header().destination(),
                        identification: header.identification,
                    },
                    ipv6.payload().ip_number,
                ),
                offset: header.fragment_offset,
                more: header.more_fragments,
                payload: ipv6.payload().payload,
                hop_limit: ipv6.header().hop_limit(),
            })
        }
        NetSlice::Arp(_) => None,
    }
}

fn fragment_id(sliced: &SlicedPacket<'_>, ip: IpFragVersionSpecId, payload_ip_number: IpNumber) -> IpFragId {
    IpFragId { vlan_ids: sliced.vlan_ids(), ip, payload_ip_number, channel_id: () }
}

fn rebuild_packet(id: &IpFragId, hop_limit: u8, payload: &[u8]) -> Option<Vec<u8>> {
    let payload_len = u16::try_from(payload.len()).ok()?;
    let mut packet = Vec::with_capacity(Ipv6Header::LEN + payload.len());
    match id.ip {
        IpFragVersionSpecId::Ipv4 { source, destination, .. } => {
            let mut header = Ipv4Header::new(payload_len, hop_limit, id.payload_ip_number, source, destination).ok()?;
            header.header_checksum = header.calc_header_checksum();
            packet.extend_from_slice(&header.to_bytes());
        }
        IpFragVersionSpecId::Ipv6 { source, destination, .. } => {
            let header = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length: payload_len,
                next_header: id.payload_ip_number,
                hop_limit,
                source,
                destination,
            };
            packet.extend_from_slice(&header.to_bytes());
        }
    }
    packet.extend_from_slice(payload);
    Some(packet)
}

#[cfg(test)]
mod tests;
