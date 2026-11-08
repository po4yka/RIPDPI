use std::net::IpAddr;

use etherparse::{Ipv6ExtensionSlice, NetSlice, SlicedPacket, TransportSlice};

use super::is_unicast;

const IPV6_ERROR_QUOTE_LEN: usize = 1280 - 40 - 8;

pub(crate) struct UdpErrorQuote {
    pub(super) original: Vec<u8>,
    pub(super) source: IpAddr,
    destination: IpAddr,
}

impl UdpErrorQuote {
    pub(crate) fn capture(packet: &[u8]) -> Option<Self> {
        let parsed = SlicedPacket::from_ip(packet).ok()?;
        let TransportSlice::Udp(udp) = parsed.transport? else { return None };
        if udp.length() < 8 {
            return None;
        }
        let header = udp.to_header();
        let (source, destination, packet_len, quote_len) = match parsed.net? {
            NetSlice::Ipv4(ip) => {
                let ip_header = ip.header();
                if ip.is_payload_fragmented()
                    || ip_header.to_header().calc_header_checksum() != ip_header.header_checksum()
                {
                    return None;
                }
                if header.checksum != 0
                    && header.calc_checksum_ipv4_raw(ip_header.source(), ip_header.destination(), udp.payload()).ok()?
                        != header.checksum
                {
                    return None;
                }
                let len = usize::from(ip_header.total_len());
                (
                    IpAddr::V4(ip_header.source_addr()),
                    IpAddr::V4(ip_header.destination_addr()),
                    len,
                    (len - ip.payload().payload.len() + 16).min(548),
                )
            }
            NetSlice::Ipv6(ip) => {
                let ip_header = ip.header();
                // Routing headers need final-destination checksum semantics; fragments need their original first header.
                if ip_header.payload_length() == 0
                    || ip
                        .extensions()
                        .clone()
                        .into_iter()
                        .any(|ext| matches!(ext, Ipv6ExtensionSlice::Routing(_) | Ipv6ExtensionSlice::Fragment(_)))
                    || header.checksum == 0
                    || header.calc_checksum_ipv6_raw(ip_header.source(), ip_header.destination(), udp.payload()).ok()?
                        != header.checksum
                {
                    return None;
                }
                let len = 40 + usize::from(ip_header.payload_length());
                (
                    IpAddr::V6(ip_header.source_addr()),
                    IpAddr::V6(ip_header.destination_addr()),
                    len,
                    IPV6_ERROR_QUOTE_LEN,
                )
            }
            NetSlice::Arp(_) => return None,
        };
        if !is_unicast(source) || !is_unicast(destination) {
            return None;
        }
        let original = packet.get(..packet_len)?.get(..packet_len.min(quote_len))?.to_vec();
        Some(Self { original, source, destination })
    }

    pub(crate) fn source(&self) -> IpAddr {
        self.source
    }

    pub(crate) fn destination(&self) -> IpAddr {
        self.destination
    }
}
