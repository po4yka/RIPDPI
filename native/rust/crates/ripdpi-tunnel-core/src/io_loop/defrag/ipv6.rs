use etherparse::defrag::IpFragVersionSpecId;
use etherparse::{IpNumber, Ipv6FragmentHeaderSlice, Ipv6Header, Ipv6HeaderSlice, UdpHeader};

use super::{Fragment, FragmentId};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum FirstFragment {
    Udp,
    Other,
    Invalid,
}

pub(super) struct Headers<'a> {
    prefix: &'a [u8],
    next_header_offset: usize,
    pub(super) next_header: IpNumber,
    pub(super) first_fragment: Option<FirstFragment>,
}

impl Headers<'_> {
    pub(super) fn without_fragment_header(&self) -> Vec<u8> {
        let mut prefix = self.prefix.to_vec();
        prefix[self.next_header_offset] = self.next_header.0;
        prefix
    }
}

pub(super) fn parse_fragment(packet: &[u8]) -> Option<Fragment<'_>> {
    let header = Ipv6HeaderSlice::from_slice(packet).ok()?;
    let packet = packet.get(..Ipv6Header::LEN + usize::from(header.payload_length()))?;
    let mut next = header.next_header();
    let mut offset = Ipv6Header::LEN;
    let mut next_header_offset = 6;
    loop {
        match next {
            IpNumber::IPV6_FRAGMENTATION_HEADER => break,
            IpNumber::IPV6_HEADER_HOP_BY_HOP | IpNumber::IPV6_DESTINATION_OPTIONS | IpNumber::IPV6_ROUTE_HEADER => {
                if next == IpNumber::IPV6_HEADER_HOP_BY_HOP && offset != Ipv6Header::LEN {
                    return None;
                }
                let extension = packet.get(offset..offset + 2)?;
                let end = offset + (usize::from(extension[1]) + 1) * 8;
                packet.get(offset..end)?;
                next = IpNumber(extension[0]);
                next_header_offset = offset;
                offset = end;
            }
            _ => return None,
        }
    }
    let fragment = Ipv6FragmentHeaderSlice::from_slice(packet.get(offset..)?).ok()?;
    if !fragment.is_fragmenting_payload() {
        return None;
    }
    let next_header = fragment.next_header();
    let payload = packet.get(offset + 8..)?;
    Some(Fragment {
        id: FragmentId {
            ip: IpFragVersionSpecId::Ipv6 {
                source: header.source(),
                destination: header.destination(),
                identification: fragment.identification(),
            },
            // IPv6 reassembly identity excludes the Fragment Next Header field.
            payload_ip_number: IpNumber::UDP,
        },
        offset: fragment.fragment_offset(),
        more: fragment.more_fragments(),
        payload,
        hop_limit: header.hop_limit(),
        ipv6_headers: Some(Headers {
            prefix: &packet[..offset],
            next_header_offset,
            next_header,
            first_fragment: (fragment.fragment_offset().byte_offset() == 0)
                .then(|| first_fragment_protocol(next_header, payload)),
        }),
    })
}

fn first_fragment_protocol(mut next: IpNumber, mut payload: &[u8]) -> FirstFragment {
    while next == IpNumber::IPV6_DESTINATION_OPTIONS {
        let Some(header) = payload.get(..2) else { return FirstFragment::Invalid };
        let len = (usize::from(header[1]) + 1) * 8;
        let Some(rest) = payload.get(len..) else { return FirstFragment::Invalid };
        next = IpNumber(header[0]);
        payload = rest;
    }
    match next {
        IpNumber::UDP if payload.len() >= UdpHeader::LEN => FirstFragment::Udp,
        IpNumber::UDP => FirstFragment::Invalid,
        _ => FirstFragment::Other,
    }
}
