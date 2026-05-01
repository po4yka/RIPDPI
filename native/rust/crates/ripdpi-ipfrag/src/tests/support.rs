pub(super) use std::net::SocketAddr;

pub(super) use etherparse::{
    ip_number, IpNumber, Ipv4Header, Ipv4HeaderSlice, Ipv6FragmentHeader, Ipv6Header, TcpHeader, TcpOptionElement,
    UdpHeader,
};

pub(super) use crate::split::IP_FRAGMENT_ALIGNMENT_BYTES;
pub(super) use crate::{
    build_fake_rst_packet, build_tcp_fragment_pair, build_udp_fragment_pair, BuildError, Ipv6ExtHeaders,
    TcpFragmentSpec, TcpTimestampOption, UdpFragmentSpec,
};

pub(super) fn reassemble_ipv4_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
    let first_header = Ipv4HeaderSlice::from_slice(first).expect("parse first ipv4 header");
    let second_header = Ipv4HeaderSlice::from_slice(second).expect("parse second ipv4 header");
    let mut transport = Vec::new();
    transport.extend_from_slice(&first[first_header.slice().len()..]);
    transport.extend_from_slice(&second[second_header.slice().len()..]);
    transport
}

/// Skip over any extension headers between the IPv6 base header and the Fragment Header.
fn skip_to_fragment_header(mut data: &[u8], mut next_header: IpNumber) -> (&[u8], IpNumber) {
    // Walk extension headers until we find the Fragment Header (44).
    while next_header != ip_number::IPV6_FRAG {
        // All extension headers have: next_header(1) + hdr_ext_len(1) + data
        assert!(data.len() >= 2, "extension header too short");
        let nh = IpNumber(data[0]);
        let hdr_len = (usize::from(data[1]) + 1) * 8;
        assert!(data.len() >= hdr_len, "extension header length exceeds data");
        data = &data[hdr_len..];
        next_header = nh;
    }
    (data, next_header)
}

pub(super) fn reassemble_ipv6_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
    let (first_base, first_rest) = Ipv6Header::from_slice(first).expect("parse first ipv6 header");
    let (first_rest, _) = skip_to_fragment_header(first_rest, first_base.next_header);
    let (_first_frag, first_payload) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

    let (second_base, second_rest) = Ipv6Header::from_slice(second).expect("parse second ipv6 header");
    let (second_rest, _) = skip_to_fragment_header(second_rest, second_base.next_header);
    let (_second_frag, second_payload) =
        Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");
    assert_eq!(first_base.destination, second_base.destination);

    let mut transport = Vec::new();
    transport.extend_from_slice(first_payload);
    transport.extend_from_slice(second_payload);
    transport
}
