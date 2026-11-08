use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};

use etherparse::{IpFragOffset, IpNumber, Ipv4Header, Ipv6FragmentHeader, Ipv6Header};

use super::build_udp_response;

static NEXT_FRAGMENT_ID: AtomicU32 = AtomicU32::new(1);

/// Build replies one packet at a time so a small MTU does not retain a list of fragments.
pub(crate) fn build_udp_response_fragments(
    src: SocketAddr,
    dst: SocketAddr,
    payload: &[u8],
    mtu: usize,
) -> impl Iterator<Item = Vec<u8>> {
    let mut packet = build_udp_response(src, dst, payload);
    let ipv6 = src.is_ipv6();
    let ip_header_len = if ipv6 { Ipv6Header::LEN } else { Ipv4Header::MIN_LEN };
    let fragment_header_len = ip_header_len + if ipv6 { Ipv6FragmentHeader::LEN } else { 0 };
    let fragment_payload_len = mtu.saturating_sub(fragment_header_len) / 8 * 8;
    let identification = if packet.len() > mtu {
        // Relaxed only allocates unique IDs; no payload or other state is published by this counter.
        NEXT_FRAGMENT_ID.fetch_add(1, Ordering::Relaxed)
    } else {
        0
    };
    let mut offset = 0;
    std::iter::from_fn(move || {
        if packet.is_empty() {
            return None;
        }
        if packet.len() <= mtu {
            return Some(std::mem::take(&mut packet));
        }
        if fragment_payload_len == 0 {
            packet.clear();
            return None;
        }
        let datagram = &packet[ip_header_len..];
        if offset >= datagram.len() {
            return None;
        }
        let end = (offset + fragment_payload_len).min(datagram.len());
        let part = &datagram[offset..end];
        let fragment_offset = IpFragOffset::try_new(u16::try_from(offset / 8).ok()?).ok()?;
        let more = end < datagram.len();
        let mut fragment = Vec::with_capacity(fragment_header_len + part.len());
        if ipv6 {
            let (mut header, _) = Ipv6Header::from_slice(&packet).ok()?;
            header.payload_length = u16::try_from(Ipv6FragmentHeader::LEN + part.len()).ok()?;
            header.next_header = IpNumber::IPV6_FRAGMENTATION_HEADER;
            fragment.extend_from_slice(&header.to_bytes());
            let header = Ipv6FragmentHeader::new(IpNumber::UDP, fragment_offset, more, identification);
            fragment.extend_from_slice(&header.to_bytes());
        } else {
            let (mut header, _) = Ipv4Header::from_slice(&packet).ok()?;
            header.set_payload_len(part.len()).ok()?;
            header.identification = identification as u16;
            header.dont_fragment = false;
            header.more_fragments = more;
            header.fragment_offset = fragment_offset;
            header.header_checksum = header.calc_header_checksum();
            fragment.extend_from_slice(&header.to_bytes());
        }
        fragment.extend_from_slice(part);
        offset = end;
        Some(fragment)
    })
    .fuse()
}
