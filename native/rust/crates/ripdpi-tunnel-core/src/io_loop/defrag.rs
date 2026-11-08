//! Bounded reassembly of fragmented IPv4 and IPv6 UDP datagrams in front of packet routing.
//!
//! The UDP path proxies whole datagrams, so a datagram larger than the TUN MTU that the app's
//! kernel fragments was lost. This module rebuilds such a datagram into one unfragmented IP packet
//! that the normal routing then handles. Other protocols keep their path: TCP segments fit the MSS,
//! and ICMP fragments must still meet the UID policy one by one.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use etherparse::defrag::{IpDefragBuf, IpFragVersionSpecId};
use etherparse::{IpFragOffset, IpNumber, Ipv4Header, Ipv6Header, NetSlice, SlicedPacket, TransportSlice};

mod ipv6;

/// Datagrams in reassembly at one time. Payloads and IPv6 header prefixes are each bounded to 64 KiB.
const MAX_REASSEMBLIES: usize = 32;
/// A datagram or rejected identity expires this long after its first fragment.
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
    // None retains a rejected IPv6 identity without its packet bytes.
    buf: Option<IpDefragBuf>,
    hop_limit: u8,
    first_seen: Instant,
    ipv6_prefix: Option<Vec<u8>>,
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct FragmentId {
    ip: IpFragVersionSpecId,
    payload_ip_number: IpNumber,
}

#[derive(Default)]
pub(in crate::io_loop) struct IpDefrag {
    pending: HashMap<FragmentId, Pending>,
}

struct Fragment<'a> {
    id: FragmentId,
    offset: IpFragOffset,
    more: bool,
    payload: &'a [u8],
    hop_limit: u8,
    ipv6_headers: Option<ipv6::Headers<'a>>,
}

impl IpDefrag {
    pub(in crate::io_loop) fn accept(&mut self, packet: &[u8], now: Instant) -> Reassembly {
        let Some(fragment) = parse_fragment(packet) else {
            return Reassembly::NotFragment;
        };
        self.pending.retain(|_, pending| now.duration_since(pending.first_seen) < REASSEMBLY_TIMEOUT);
        if !self.pending.contains_key(&fragment.id) && self.pending.len() >= MAX_REASSEMBLIES {
            return Reassembly::Held;
        }
        let pending = self.pending.entry(fragment.id.clone()).or_insert_with(|| Pending {
            buf: Some(IpDefragBuf::new(fragment.id.payload_ip_number, Vec::new(), Vec::new())),
            hop_limit: fragment.hop_limit,
            first_seen: now,
            ipv6_prefix: None,
        });
        let Some(buf) = pending.buf.as_mut() else {
            return Reassembly::Held;
        };
        let ipv6 = matches!(fragment.id.ip, IpFragVersionSpecId::Ipv6 { .. });
        if let Some(headers) = &fragment.ipv6_headers
            && !headers.valid_first_fragment
        {
            pending.buf = None;
            pending.ipv6_prefix = None;
            return Reassembly::Held;
        }
        let start = usize::from(fragment.offset.byte_offset());
        let end = start + fragment.payload.len();
        if ipv6 && buf.sections().iter().any(|range| start < usize::from(range.end) && usize::from(range.start) < end) {
            // RFC 8200 permits dropping exact duplicates with all other overlaps.
            pending.buf = None;
            pending.ipv6_prefix = None;
            return Reassembly::Held;
        }
        if let Some(headers) = &fragment.ipv6_headers
            && start == 0
        {
            pending.ipv6_prefix = Some(headers.without_fragment_header());
        }
        if buf.add(fragment.offset, fragment.more, fragment.payload).is_err() {
            if ipv6 {
                pending.buf = None;
                pending.ipv6_prefix = None;
            } else {
                self.pending.remove(&fragment.id);
            }
            return Reassembly::Held;
        }
        if !buf.is_complete() {
            return Reassembly::Held;
        }
        let Some(pending) = self.pending.remove(&fragment.id) else {
            return Reassembly::Held;
        };
        let Some(buf) = pending.buf else {
            return Reassembly::Held;
        };
        let (payload, _) = buf.take_bufs();
        rebuild_packet(&fragment.id, pending.hop_limit, pending.ipv6_prefix, &payload)
            .map_or(Reassembly::Held, Reassembly::Complete)
    }
}

fn parse_fragment(packet: &[u8]) -> Option<Fragment<'_>> {
    if !may_be_udp_fragment(packet) {
        return None;
    }
    if packet.first().map(|byte| byte >> 4) == Some(6) {
        ipv6::parse_fragment(packet)
    } else {
        parse_ip_fragment(packet).filter(|fragment| fragment.id.payload_ip_number == IpNumber::UDP)
    }
}

/// Reads only fixed header bytes, so a bulk packet skips the full parse on the per-packet path.
/// IPv4: a UDP packet with the MF flag or a fragment offset. IPv6: any next header other than TCP,
/// UDP or ICMPv6, because a Fragment header can follow other extension headers.
fn may_be_udp_fragment(packet: &[u8]) -> bool {
    const FRAGMENT_BITS: u16 = 0x3fff;
    match packet.first().map(|byte| byte >> 4) {
        Some(4) => match (packet.get(6..8), packet.get(9)) {
            (Some(&[high, low]), Some(&protocol)) => {
                protocol == IpNumber::UDP.0 && u16::from_be_bytes([high, low]) & FRAGMENT_BITS != 0
            }
            _ => false,
        },
        Some(6) => packet
            .get(6)
            .is_some_and(|&next| ![IpNumber::TCP.0, IpNumber::UDP.0, IpNumber::IPV6_ICMP.0].contains(&next)),
        _ => false,
    }
}

fn parse_ip_fragment(packet: &[u8]) -> Option<Fragment<'_>> {
    let sliced = SlicedPacket::from_ip(packet).ok()?;
    match sliced.net.as_ref()? {
        NetSlice::Ipv4(ipv4) => {
            let header = ipv4.header();
            header.is_fragmenting_payload().then(|| Fragment {
                id: FragmentId {
                    ip: IpFragVersionSpecId::Ipv4 {
                        source: header.source(),
                        destination: header.destination(),
                        identification: header.identification(),
                    },
                    payload_ip_number: ipv4.payload().ip_number,
                },
                offset: header.fragments_offset(),
                more: header.more_fragments(),
                payload: ipv4.payload().payload,
                hop_limit: header.ttl(),
                ipv6_headers: None,
            })
        }
        NetSlice::Ipv6(_) => None,
        NetSlice::Arp(_) => None,
    }
}

fn rebuild_packet(id: &FragmentId, hop_limit: u8, ipv6_prefix: Option<Vec<u8>>, payload: &[u8]) -> Option<Vec<u8>> {
    let payload_len = u16::try_from(payload.len()).ok()?;
    let mut packet = match id.ip {
        IpFragVersionSpecId::Ipv4 { source, destination, .. } => {
            let mut header = Ipv4Header::new(payload_len, hop_limit, id.payload_ip_number, source, destination).ok()?;
            header.header_checksum = header.calc_header_checksum();
            let mut packet = Vec::with_capacity(Ipv4Header::MIN_LEN + payload.len());
            packet.extend_from_slice(&header.to_bytes());
            packet
        }
        IpFragVersionSpecId::Ipv6 { .. } => {
            let mut packet = ipv6_prefix?;
            let payload_len = packet.len().checked_sub(Ipv6Header::LEN)?.checked_add(payload.len())?;
            packet[4..6].copy_from_slice(&u16::try_from(payload_len).ok()?.to_be_bytes());
            packet
        }
    };
    packet.extend_from_slice(payload);
    if matches!(id.ip, IpFragVersionSpecId::Ipv6 { .. })
        && !matches!(SlicedPacket::from_ip(&packet).ok()?.transport, Some(TransportSlice::Udp(_)))
    {
        return None;
    }
    Some(packet)
}

#[cfg(test)]
mod tests;
