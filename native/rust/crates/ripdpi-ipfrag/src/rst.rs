use std::net::SocketAddr;

use etherparse::{ip_number, Ipv4Header, Ipv6FlowLabel, Ipv6Header, TcpHeader};

use crate::ipv4::serialize_ipv4_fragment;
use crate::tcp::{apply_tcp_flag_overrides_to_transport, serialize_tcp_transport};
use crate::{BuildError, TcpFragmentSpec};

/// Build a single TCP RST+ACK packet for fake RST injection.
///
/// The packet has the correct seq/ack from the live connection but a low TTL
/// so it expires before reaching the server.  DPI processes the RST and clears
/// its connection-tracking state while the real connection continues normally.
pub fn build_fake_rst_packet(spec: &TcpFragmentSpec) -> Result<Vec<u8>, BuildError> {
    let mut tcp = TcpHeader::new(spec.src.port(), spec.dst.port(), spec.sequence_number, 0);
    tcp.rst = true;
    tcp.ack = true;
    tcp.acknowledgment_number = spec.acknowledgment_number;
    // RST packets conventionally carry window=0.
    let payload: &[u8] = &[];

    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let mut ip = Ipv4Header::new(
                u16::try_from(transport.len()).map_err(|_| BuildError::ValueTooLarge)?,
                spec.ttl,
                ip_number::TCP,
                src.ip().octets(),
                dst.ip().octets(),
            )
            .map_err(|_| BuildError::ValueTooLarge)?;
            ip.identification = spec.identification as u16;
            ip.dont_fragment = true;
            ip.header_checksum = ip.calc_header_checksum();
            Ok(serialize_ipv4_fragment(&ip, &transport))
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length: u16::try_from(transport.len()).map_err(|_| BuildError::ValueTooLarge)?,
                next_header: ip_number::TCP,
                hop_limit: spec.ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };
            let mut buf = Vec::with_capacity(Ipv6Header::LEN + transport.len());
            ip.write(&mut buf).expect("Vec<u8> write must not fail");
            buf.extend_from_slice(&transport);
            Ok(buf)
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}
