use std::net::SocketAddr;

use etherparse::{Ipv6FlowLabel, Ipv6Header, UdpHeader, ip_number};

use crate::ipv4::build_ipv4_fragment_pair;
use crate::ipv6::build_ipv6_fragment_pair;
use crate::split::resolve_effective_split;
use crate::{BuildError, IpFragmentPair, UdpFragmentSpec};

pub fn build_udp_fragment_pair(
    spec: UdpFragmentSpec,
    payload: &[u8],
    minimum_transport_split: usize,
) -> Result<IpFragmentPair, BuildError> {
    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let mut udp = UdpHeader::without_ipv4_checksum(src.port(), dst.port(), payload.len())
                .map_err(|_| BuildError::ValueTooLarge)?;
            udp.checksum = udp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_udp_transport(udp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv4_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification as u16,
                ip_number::UDP,
                &transport,
                split,
            )
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length: u16::try_from(UdpHeader::LEN + payload.len()).map_err(|_| BuildError::ValueTooLarge)?,
                next_header: ip_number::UDP,
                hop_limit: spec.ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };
            let udp = UdpHeader::with_ipv6_checksum(src.port(), dst.port(), &ip, payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_udp_transport(udp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv6_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification,
                ip_number::UDP,
                &transport,
                split,
                spec.ipv6_ext,
            )
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}

fn serialize_udp_transport(header: UdpHeader, payload: &[u8]) -> Vec<u8> {
    let mut transport = Vec::with_capacity(UdpHeader::LEN + payload.len());
    header.write(&mut transport).expect("Vec<u8> write must not fail");
    transport.extend_from_slice(payload);
    transport
}
