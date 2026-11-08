use etherparse::{IpNumber, Ipv6ExtensionSlice, NetSlice, SlicedPacket, TransportSlice};

/// Remove extensions already checked by the routing policy before smoltcp handles TCP.
pub(crate) fn normalize_ipv6_tcp_packet(packet: &[u8]) -> Option<Vec<u8>> {
    if packet.first().is_none_or(|byte| byte >> 4 != 6) {
        return None;
    }
    let parsed = SlicedPacket::from_ip(packet).ok()?;
    let NetSlice::Ipv6(ip) = parsed.net? else { return None };
    let TransportSlice::Tcp(tcp) = parsed.transport? else { return None };
    if ip.extensions().slice().is_empty()
        || ip.extensions().clone().into_iter().any(|extension| match extension {
            Ipv6ExtensionSlice::HopByHop(_) | Ipv6ExtensionSlice::DestinationOptions(_) => false,
            Ipv6ExtensionSlice::Fragment(fragment) => fragment.is_fragmenting_payload(),
            _ => true,
        })
    {
        return None;
    }
    let mut header = ip.header().to_header();
    header.payload_length = u16::try_from(tcp.slice().len()).ok()?;
    header.next_header = IpNumber::TCP;
    let mut result = Vec::with_capacity(40 + tcp.slice().len());
    result.extend_from_slice(&header.to_bytes());
    result.extend_from_slice(tcp.slice());
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalization_keeps_tcp_options_and_odd_payload() {
        // Independent Scapy input/output: HBH + Destination Options, timestamp option, seven data bytes.
        let input = hex::decode("6000000000370040fd00000000000000000000000000000220010db80000000000000000000000093c0001040000000006000104000000009c4001bb00000064000000c880182000ce500000080a0000007b000001c800007061796c6f6164").unwrap();
        let expected = hex::decode("6000000000270640fd00000000000000000000000000000220010db80000000000000000000000099c4001bb00000064000000c880182000ce500000080a0000007b000001c800007061796c6f6164").unwrap();
        assert_eq!(normalize_ipv6_tcp_packet(&input).unwrap(), expected);
    }

    #[test]
    fn long_padding_chains_are_bounded_by_packet_length() {
        let mut input = hex::decode("6000000000140640fd00000000000000000000000000000220010db80000000000000000000000099c4001bb000000640000000050022000c6be0000").unwrap();
        let expected = input.clone();
        let tcp = input.split_off(40);
        input[6] = 60;
        input[4..6].copy_from_slice(&100u16.to_be_bytes());
        for index in 0..10 {
            input.extend_from_slice(&[if index == 9 { 6 } else { 60 }, 0, 1, 4, 0, 0, 0, 0]);
        }
        input.extend_from_slice(&tcp);
        assert_eq!(normalize_ipv6_tcp_packet(&input).unwrap(), expected);
    }
}
