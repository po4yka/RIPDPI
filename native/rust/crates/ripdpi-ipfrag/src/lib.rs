#![forbid(unsafe_code)]

use std::net::SocketAddr;

use etherparse::{
    ip_number, IpFragOffset, IpNumber, Ipv4Header, Ipv6FlowLabel, Ipv6FragmentHeader, Ipv6Header, TcpHeader,
    TcpOptionElement, UdpHeader,
};
use thiserror::Error;

const IP_FRAGMENT_ALIGNMENT_BYTES: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IpFragmentPair {
    pub first: Vec<u8>,
    pub second: Vec<u8>,
    pub effective_transport_split: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
    pub sequence_number: u32,
    pub acknowledgment_number: u32,
    pub window_size: u16,
    pub timestamp: Option<TcpTimestampOption>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpTimestampOption {
    pub value: u32,
    pub echo_reply: u32,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum BuildError {
    #[error("source and destination socket addresses must use the same IP family")]
    AddressFamilyMismatch,
    #[error("minimum split {requested} rounds to {effective}, which does not leave two non-empty IP fragments for transport length {transport_len}")]
    InvalidSplit { requested: usize, effective: usize, transport_len: usize },
    #[error("fragment payload exceeds protocol limits")]
    ValueTooLarge,
}

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
            )
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}

pub fn build_tcp_fragment_pair(
    spec: TcpFragmentSpec,
    payload: &[u8],
    minimum_payload_split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let mut tcp = TcpHeader::new(spec.src.port(), spec.dst.port(), spec.sequence_number, spec.window_size);
    tcp.ack = true;
    tcp.psh = !payload.is_empty();
    tcp.acknowledgment_number = spec.acknowledgment_number;
    if let Some(timestamp) = spec.timestamp {
        tcp.set_options(&[
            TcpOptionElement::Noop,
            TcpOptionElement::Noop,
            TcpOptionElement::Timestamp(timestamp.value, timestamp.echo_reply),
        ])
        .map_err(|_| BuildError::ValueTooLarge)?;
    }

    let minimum_transport_split =
        tcp.header_len().checked_add(minimum_payload_split).ok_or(BuildError::ValueTooLarge)?;

    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_tcp_transport(&tcp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv4_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification as u16,
                ip_number::TCP,
                &transport,
                split,
            )
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_tcp_transport(&tcp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv6_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification,
                ip_number::TCP,
                &transport,
                split,
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

fn serialize_tcp_transport(header: &TcpHeader, payload: &[u8]) -> Vec<u8> {
    let mut transport = Vec::with_capacity(header.header_len() + payload.len());
    header.write(&mut transport).expect("Vec<u8> write must not fail");
    transport.extend_from_slice(payload);
    transport
}

fn resolve_effective_split(requested: usize, transport_len: usize) -> Result<usize, BuildError> {
    let effective = requested
        .checked_add(IP_FRAGMENT_ALIGNMENT_BYTES - 1)
        .map(|value| (value / IP_FRAGMENT_ALIGNMENT_BYTES) * IP_FRAGMENT_ALIGNMENT_BYTES)
        .ok_or(BuildError::ValueTooLarge)?;
    if effective == 0 || effective >= transport_len {
        return Err(BuildError::InvalidSplit { requested, effective, transport_len });
    }
    Ok(effective)
}

fn build_ipv4_fragment_pair(
    src: [u8; 4],
    dst: [u8; 4],
    ttl: u8,
    identification: u16,
    protocol: IpNumber,
    transport: &[u8],
    split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let first = &transport[..split];
    let second = &transport[split..];

    let mut first_header =
        Ipv4Header::new(u16::try_from(first.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    first_header.identification = identification;
    first_header.dont_fragment = false;
    first_header.more_fragments = true;
    first_header.header_checksum = first_header.calc_header_checksum();

    let mut second_header =
        Ipv4Header::new(u16::try_from(second.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.identification = identification;
    second_header.dont_fragment = false;
    second_header.more_fragments = false;
    second_header.fragment_offset = IpFragOffset::try_new(
        u16::try_from(split / IP_FRAGMENT_ALIGNMENT_BYTES).map_err(|_| BuildError::ValueTooLarge)?,
    )
    .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.header_checksum = second_header.calc_header_checksum();

    let first_bytes = serialize_ipv4_fragment(&first_header, first);
    let second_bytes = serialize_ipv4_fragment(&second_header, second);
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

fn serialize_ipv4_fragment(header: &Ipv4Header, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + payload.len());
    header.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(payload);
    bytes
}

fn build_ipv6_fragment_pair(
    src: [u8; 16],
    dst: [u8; 16],
    ttl: u8,
    identification: u32,
    next_header: IpNumber,
    transport: &[u8],
    split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let first = &transport[..split];
    let second = &transport[split..];

    let base_first = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(Ipv6FragmentHeader::LEN + first.len()).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ip_number::IPV6_FRAG,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };
    let base_second = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(Ipv6FragmentHeader::LEN + second.len()).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ip_number::IPV6_FRAG,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };
    let first_fragment = Ipv6FragmentHeader::new(next_header, IpFragOffset::ZERO, true, identification);
    let second_fragment = Ipv6FragmentHeader::new(
        next_header,
        IpFragOffset::try_new(
            u16::try_from(split / IP_FRAGMENT_ALIGNMENT_BYTES).map_err(|_| BuildError::ValueTooLarge)?,
        )
        .map_err(|_| BuildError::ValueTooLarge)?,
        false,
        identification,
    );

    let first_bytes = serialize_ipv6_fragment(&base_first, &first_fragment, first);
    let second_bytes = serialize_ipv6_fragment(&base_second, &second_fragment, second);
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

fn serialize_ipv6_fragment(base: &Ipv6Header, fragment: &Ipv6FragmentHeader, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(Ipv6Header::LEN + Ipv6FragmentHeader::LEN + payload.len());
    base.write(&mut bytes).expect("Vec<u8> write must not fail");
    fragment.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(payload);
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    use etherparse::{Ipv4Header, Ipv4HeaderSlice, Ipv6FragmentHeader, Ipv6Header, TcpHeader, UdpHeader};

    fn reassemble_ipv4_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
        let first_header = Ipv4HeaderSlice::from_slice(first).expect("parse first ipv4 header");
        let second_header = Ipv4HeaderSlice::from_slice(second).expect("parse second ipv4 header");
        let mut transport = Vec::new();
        transport.extend_from_slice(&first[first_header.slice().len()..]);
        transport.extend_from_slice(&second[second_header.slice().len()..]);
        transport
    }

    fn reassemble_ipv6_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
        let (first_base, first_rest) = Ipv6Header::from_slice(first).expect("parse first ipv6 header");
        let (_first_frag, first_payload) =
            Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");
        let (second_base, second_rest) = Ipv6Header::from_slice(second).expect("parse second ipv6 header");
        let (_second_frag, second_payload) =
            Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");
        assert_eq!(first_base.destination, second_base.destination);

        let mut transport = Vec::new();
        transport.extend_from_slice(first_payload);
        transport.extend_from_slice(second_payload);
        transport
    }

    #[test]
    fn udp_ipv4_fragment_pair_clears_df_and_preserves_udp_checksum() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([192, 0, 2, 10], 40000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x1234,
        };
        let payload = b"quic initial payload";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv4 fragments");
        assert_eq!(pair.effective_transport_split, 8);

        let (first_header, _) = Ipv4Header::from_slice(&pair.first).expect("parse first ipv4 header");
        let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");
        assert!(!first_header.dont_fragment);
        assert!(first_header.more_fragments);
        assert_eq!(u16::from(first_header.fragment_offset), 0);
        assert!(!second_header.more_fragments);
        assert_eq!(second_header.fragment_offset.byte_offset() as usize, 8);
        assert_eq!(first_header.header_checksum, first_header.calc_header_checksum());
        assert_eq!(second_header.header_checksum, second_header.calc_header_checksum());

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp transport");
        assert_eq!(udp.source_port, 40000);
        assert_eq!(udp.destination_port, 443);
        assert_eq!(udp_payload, payload);
        assert_eq!(
            udp.checksum,
            udp.calc_checksum_ipv4_raw([192, 0, 2, 10], [198, 51, 100, 20], udp_payload)
                .expect("recalculate udp checksum")
        );
    }

    #[test]
    fn udp_ipv6_fragment_pair_adds_fragment_header() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
        };
        let payload = b"hello over fragmented udp";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv6 fragments");
        let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
        let (first_fragment, first_payload) =
            Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");
        let (second_base, second_rest) = Ipv6Header::from_slice(&pair.second).expect("parse second ipv6 header");
        let (second_fragment, second_payload) =
            Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");

        assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(second_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(first_fragment.next_header, ip_number::UDP);
        assert!(first_fragment.more_fragments);
        assert_eq!(u16::from(first_fragment.fragment_offset), 0);
        assert_eq!(second_fragment.fragment_offset.byte_offset() as usize, pair.effective_transport_split);
        assert!(!second_fragment.more_fragments);
        assert_eq!(first_payload.len(), pair.effective_transport_split);
        assert!(!second_payload.is_empty());

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse reassembled udp transport");
        assert_eq!(udp_payload, payload);
        assert_eq!(
            udp.checksum,
            udp.calc_checksum_ipv6_raw(
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
                udp_payload
            )
            .expect("recalculate ipv6 udp checksum")
        );
    }

    #[test]
    fn tcp_ipv4_fragment_pair_preserves_sequence_ack_and_checksum() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x9988,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
        };
        let payload = b"fragmented tls client hello";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments");
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
        assert!(pair.effective_transport_split > TcpHeader::MIN_LEN);

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        assert_eq!(tcp.sequence_number, spec.sequence_number);
        assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
        assert!(tcp.ack);
        assert!(tcp.psh);
        assert_eq!(tcp_payload, payload);
        assert_eq!(
            tcp.checksum,
            tcp.calc_checksum_ipv4_raw([203, 0, 113, 10], [198, 51, 100, 20], tcp_payload)
                .expect("recalculate tcp checksum")
        );
    }

    #[test]
    fn tcp_ipv6_fragment_pair_preserves_sequence_ack_and_checksum() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
        };
        let payload = b"fragmented tls client hello over ipv6";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv6 fragments");
        let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
        let (first_fragment, _) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

        assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(first_fragment.next_header, ip_number::TCP);
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse reassembled tcp transport");
        assert_eq!(tcp.sequence_number, spec.sequence_number);
        assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
        assert!(tcp.ack);
        assert!(tcp.psh);
        assert_eq!(tcp_payload, payload);
        assert_eq!(
            tcp.checksum,
            tcp.calc_checksum_ipv6_raw(
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
                tcp_payload
            )
            .expect("recalculate ipv6 tcp checksum")
        );
    }

    #[test]
    fn tcp_fragment_pair_rounds_payload_split_up_to_next_ip_boundary() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x9988,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
        };
        let payload = b"fragmented tls client hello";

        let pair = build_tcp_fragment_pair(spec, payload, 1).expect("build aligned tcp fragment pair");
        let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");

        assert_eq!(pair.effective_transport_split, 24);
        assert_eq!(second_header.fragment_offset.byte_offset() as usize, 24);
    }

    #[test]
    fn degenerate_fragment_pair_is_rejected_after_alignment() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([192, 0, 2, 10], 40000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 7,
        };
        let payload = b"tiny";

        let err = build_udp_fragment_pair(spec, payload, 16).expect_err("reject degenerate udp fragment pair");
        assert!(matches!(err, BuildError::InvalidSplit { .. }));
    }

    #[test]
    fn tcp_ipv4_fragment_pair_serializes_timestamp_option_when_requested() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x1111,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: Some(TcpTimestampOption { value: 0x1122_3344, echo_reply: 0 }),
        };
        let payload = b"timestamped payload";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments with timestamp");
        assert_eq!(pair.effective_transport_split, 40);

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        let options = tcp.options_iterator().collect::<Vec<_>>();

        assert_eq!(tcp.header_len(), 32);
        assert_eq!(
            options,
            vec![
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Timestamp(0x1122_3344, 0)),
            ]
        );
        assert_eq!(tcp_payload, payload);
    }

    #[test]
    fn tcp_ipv6_fragment_pair_serializes_timestamp_option_when_requested() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: Some(TcpTimestampOption { value: 0x5566_7788, echo_reply: 0 }),
        };
        let payload = b"ipv6 timestamped payload";

        let pair = build_tcp_fragment_pair(spec, payload, 3).expect("build tcp ipv6 fragments with timestamp");
        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        let options = tcp.options_iterator().collect::<Vec<_>>();

        assert_eq!(tcp.header_len(), 32);
        assert_eq!(
            options,
            vec![
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Timestamp(0x5566_7788, 0)),
            ]
        );
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
        assert_eq!(tcp_payload, payload);
    }
}
