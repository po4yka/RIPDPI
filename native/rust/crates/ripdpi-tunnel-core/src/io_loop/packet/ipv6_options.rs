use std::net::Ipv6Addr;

use etherparse::{Ipv6FragmentHeaderSlice, Ipv6HeaderSlice, Ipv6RawExtHeaderSlice, PacketBuilder};

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Ipv6OptionDisposition {
    Accept,
    Discard,
    ParameterProblem(u32),
}

pub(crate) fn ipv6_option_disposition(packet: &[u8]) -> Ipv6OptionDisposition {
    use Ipv6OptionDisposition::{Accept, Discard, ParameterProblem};
    if packet.first().is_none_or(|byte| byte >> 4 != 6) {
        return Accept;
    }
    let Ok(ip) = Ipv6HeaderSlice::from_slice(packet) else { return Discard };
    if ip.payload_length() == 0 && packet.len() > 40 {
        return Discard;
    }
    let Some(packet) = packet.get(..40 + usize::from(ip.payload_length())) else { return Discard };
    let multicast = ip.destination_addr().is_multicast();
    let mut next = ip.next_header().0;
    let mut offset = 40;
    let mut problem = None;
    loop {
        match next {
            0 | 60 => {
                if next == 0 && offset != 40 {
                    return Discard;
                }
                let Ok(header) = Ipv6RawExtHeaderSlice::from_slice(&packet[offset..]) else { return Discard };
                let options = header.payload();
                let mut position = 0;
                while problem.is_none() && position < options.len() {
                    let kind = options[position];
                    if kind == 0 {
                        position += 1;
                        continue;
                    }
                    let Some(&length) = options.get(position + 1) else { return Discard };
                    let end = position + 2 + usize::from(length);
                    if end > options.len()
                        || (kind == 1 && options[position + 2..end].iter().any(|&b| b != 0))
                        || (kind == 5 && length != 2)
                    {
                        return Discard;
                    }
                    if ![1, 5].contains(&kind) {
                        match kind >> 6 {
                            0 => {}
                            1 => return Discard,
                            2 => problem = Some((offset + 2 + position) as u32),
                            3 if !multicast => problem = Some((offset + 2 + position) as u32),
                            _ => return Discard,
                        }
                    }
                    position = end;
                }
                next = header.next_header().0;
                offset += header.slice().len();
            }
            43 => {
                let Ok(header) = Ipv6RawExtHeaderSlice::from_slice(&packet[offset..]) else { return Discard };
                // Active source routing needs a different pseudo-header destination.
                if header.slice()[3] != 0 {
                    return Discard;
                }
                next = header.next_header().0;
                offset += header.slice().len();
            }
            44 => {
                let Ok(header) = Ipv6FragmentHeaderSlice::from_slice(&packet[offset..]) else { return Discard };
                if header.is_fragmenting_payload() {
                    // Fragmentable bytes may start in the middle of an extension header.
                    return if problem.is_some() { Discard } else { Accept };
                }
                next = header.next_header().0;
                offset += header.slice().len();
            }
            50 | 51 => return Discard,
            _ => return problem.map_or(Accept, ParameterProblem),
        }
    }
}

pub(crate) fn build_ipv6_option_error(packet: &[u8], pointer: u32, source: Ipv6Addr, mtu: usize) -> Option<Vec<u8>> {
    use etherparse::{Icmpv6Type, IpNumber, TransportSlice, icmpv6};
    if source.is_unspecified() || source.is_multicast() {
        return None;
    }
    let parsed = etherparse::SlicedPacket::from_ip(packet).ok()?;
    let etherparse::NetSlice::Ipv6(ip) = parsed.net.as_ref()? else { return None };
    let destination = ip.header().source_addr();
    if ip.header().payload_length() == 0
        || destination.is_unspecified()
        || destination.is_multicast()
        || ip.payload().fragmented
    {
        return None;
    }
    // Do not send an error for a corrupt upper-layer packet or another ICMPv6 error.
    match parsed.transport.as_ref()? {
        TransportSlice::Udp(_) if crate::classify::classify_ip_packet_with_parse_status(packet, None).1 => {}
        TransportSlice::Tcp(tcp)
            if smoltcp::wire::TcpPacket::new_checked(tcp.slice()).ok()?.verify_checksum(
                &smoltcp::wire::IpAddress::Ipv6(ip.header().source_addr()),
                &smoltcp::wire::IpAddress::Ipv6(ip.header().destination_addr()),
            ) => {}
        TransportSlice::Icmpv6(icmp)
            if ip.payload().ip_number == IpNumber::IPV6_ICMP
                && icmp.type_u8() >= 128
                && icmp.is_checksum_valid(ip.header().source(), ip.header().destination()) => {}
        _ => return None,
    }
    let quote_len =
        packet.len().min(40 + usize::from(ip.header().payload_length())).min(mtu.min(1280).checked_sub(48)?);
    let mut result = Vec::new();
    PacketBuilder::ipv6(source.octets(), destination.octets(), 64)
        .icmpv6(Icmpv6Type::ParameterProblem(icmpv6::ParameterProblemHeader {
            code: icmpv6::ParameterProblemCode::UnrecognizedIpv6Option,
            pointer,
        }))
        .write(&mut result, &packet[..quote_len])
        .ok()?;
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Scapy 2.6.1 computes these headers and the independent ICMPv6 checksum.
    const INPUT: &str = "6000000000130040fd00000000000000000000000000000220010db800000000000000000000000911008002585801009c4001bb000bb2d5414243";
    const ERROR: &str = "6000000000433a40fd000000000000000000000000000001fd0000000000000000000000000000020402b6bf0000002a6000000000130040fd00000000000000000000000000000220010db800000000000000000000000911008002585801009c4001bb000bb2d5414243";

    #[test]
    fn option_error_matches_independent_packet_and_checksum() {
        let packet = hex::decode(INPUT).unwrap();
        assert_eq!(ipv6_option_disposition(&packet), Ipv6OptionDisposition::ParameterProblem(42));
        let output = build_ipv6_option_error(&packet, 42, "fd00::1".parse().unwrap(), 1500).unwrap();
        assert_eq!(output, hex::decode(ERROR).unwrap());
    }

    #[test]
    fn option_actions_respect_multicast_and_invalid_inputs() {
        let original = hex::decode(INPUT).unwrap();
        for (kind, multicast, expected) in [
            (0x40, false, Ipv6OptionDisposition::Discard),
            (0x80, true, Ipv6OptionDisposition::ParameterProblem(42)),
            (0xc0, true, Ipv6OptionDisposition::Discard),
            (0xc0, false, Ipv6OptionDisposition::ParameterProblem(42)),
        ] {
            let mut packet = original.clone();
            packet[42] = kind;
            if multicast {
                packet[24] = 0xff;
            }
            assert_eq!(ipv6_option_disposition(&packet), expected);
        }
        for length in 0..original.len() {
            assert!(build_ipv6_option_error(&original[..length], 42, "fd00::1".parse().unwrap(), 1500).is_none());
        }
        let mut corrupt = original;
        *corrupt.last_mut().unwrap() ^= 1;
        assert!(build_ipv6_option_error(&corrupt, 42, "fd00::1".parse().unwrap(), 1500).is_none());
    }

    #[test]
    fn negative_zero_tcp_checksum_still_allows_option_feedback() {
        // Scapy in6_chksum verifies the full TCP sum; zero has wire value ffff here.
        let input = hex::decode("60000000001e0040fd00000000000000000000000000000220010db800000000000000000000000906008002585801009c4001bb000000640000000050022000ffff0000c6bc").unwrap();
        let expected = hex::decode("60000000004e3a40fd000000000000000000000000000001fd0000000000000000000000000000020402c1a90000002a60000000001e0040fd00000000000000000000000000000220010db800000000000000000000000906008002585801009c4001bb000000640000000050022000ffff0000c6bc").unwrap();
        assert_eq!(build_ipv6_option_error(&input, 42, "fd00::1".parse().unwrap(), 1500).unwrap(), expected);
    }
    #[test]
    fn fragment_policy_stops_before_nonfirst_fragmentable_bytes() {
        let original = hex::decode(INPUT).unwrap();
        for option in [0x1e, 0x40, 0x80] {
            let mut fragment = original[..40].to_vec();
            fragment[4..6].copy_from_slice(&24u16.to_be_bytes());
            fragment.extend_from_slice(&[44, 0, option, 2, 0, 0, 0, 0]);
            fragment.extend_from_slice(&[60, 0, 0, 9, 0, 0, 0, 99]);
            fragment.extend_from_slice(&[0xff; 8]);
            assert_eq!(
                ipv6_option_disposition(&fragment),
                if option == 0x1e { Ipv6OptionDisposition::Accept } else { Ipv6OptionDisposition::Discard },
                "non-first fragment data is not an extension header; fragment errors stay silent",
            );
        }
    }
}
