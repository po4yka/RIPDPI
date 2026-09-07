use std::io;
use std::net::{IpAddr, SocketAddr};

use etherparse::{NetSlice, SlicedPacket, TcpHeaderSlice, TransportSlice};
use ripdpi_strategy_config::{StepType, parse_yaml_str};
use tracing::{debug, warn};

const IPV4_TTL_OFFSET: usize = 8;
const IPV4_CHECKSUM_OFFSET: usize = 10;
const IPV6_HOP_LIMIT_OFFSET: usize = 7;
const TCP_SEQUENCE_OFFSET: usize = 4;
const TCP_CHECKSUM_OFFSET: usize = 16;
const DEFAULT_SYNACK_SPLIT_SEQUENCE_DELTA: u32 = 0x4000_0000;

pub trait SynAckPacketInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()>;
}

pub struct RawSynAckPacketInjector {
    protect_path: Option<String>,
}

impl RawSynAckPacketInjector {
    pub fn new(protect_path: Option<String>) -> Self {
        Self { protect_path }
    }
}

impl SynAckPacketInjector for RawSynAckPacketInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()> {
        let Some(protect_path) = self.protect_path.as_deref() else {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "SYN-ACK raw injection requires VPN socket protection",
            ));
        };
        let target = packet_destination(packet)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "SYN-ACK packet has no TCP destination"))?;
        ripdpi_runtime_platform::experimental::send_raw_ip_packet(target, packet, Some(protect_path))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SynAckStrategy {
    LowTtl { ttl: u8 },
    Split { sequence_delta: u32 },
}

pub struct TunIngressInterceptor<I> {
    strategy: Option<SynAckStrategy>,
    injector: I,
}

impl<I: SynAckPacketInjector> TunIngressInterceptor<I> {
    #[cfg(test)]
    pub(crate) fn disabled(injector: I) -> Self {
        Self { strategy: None, injector }
    }

    #[cfg(test)]
    fn with_strategy(strategy: SynAckStrategy, injector: I) -> Self {
        Self { strategy: Some(strategy), injector }
    }

    pub fn new(strategy: Option<SynAckStrategy>, injector: I) -> Self {
        Self { strategy, injector }
    }

    pub fn handle_packet(&mut self, packet: &[u8]) {
        let Some(strategy) = self.strategy else {
            return;
        };
        if !is_synack(packet) {
            return;
        }

        let Some(packets) = strategy.injection_packets(packet) else {
            return;
        };
        for packet in packets {
            if let Err(error) = self.injector.inject_packet(&packet) {
                debug!("SYN-ACK packet injection failed; forwarding original unchanged: {error}");
                break;
            }
        }
    }
}

impl SynAckStrategy {
    #[cfg(test)]
    fn low_ttl(ttl: u8) -> Self {
        Self::LowTtl { ttl }
    }

    #[cfg(test)]
    fn split() -> Self {
        Self::Split { sequence_delta: DEFAULT_SYNACK_SPLIT_SEQUENCE_DELTA }
    }

    pub fn from_yaml(yaml: Option<&str>) -> Option<Self> {
        let yaml = yaml?.trim();
        if yaml.is_empty() {
            return None;
        }
        let parsed = match parse_yaml_str(yaml, ".") {
            Ok(parsed) => parsed,
            Err(error) => {
                warn!("failed to parse strategy YAML for SYN-ACK TUN interception: {error}");
                return None;
            }
        };
        parsed.strategies.iter().flat_map(|strategy| strategy.steps.iter()).find_map(|step| match &step.kind {
            StepType::SynAck => Some(Self::LowTtl { ttl: step.ttl.unwrap_or(5).max(1) }),
            StepType::SynAckSplit => Some(Self::Split { sequence_delta: DEFAULT_SYNACK_SPLIT_SEQUENCE_DELTA }),
            _ => None,
        })
    }

    fn injection_packets(self, packet: &[u8]) -> Option<Vec<Vec<u8>>> {
        match self {
            Self::LowTtl { ttl } => Some(vec![with_low_ttl(packet, ttl)?]),
            Self::Split { sequence_delta } => {
                let fake = with_sequence_delta(packet, sequence_delta)?;
                Some(vec![fake, packet.to_vec()])
            }
        }
    }
}

fn parse_tcp_slices(pkt: &[u8]) -> Option<(NetSlice<'_>, etherparse::TcpSlice<'_>)> {
    let parsed = SlicedPacket::from_ip(pkt).ok()?;
    let net = parsed.net?;
    match parsed.transport? {
        TransportSlice::Tcp(tcp) => Some((net, tcp)),
        _ => None,
    }
}

fn is_synack(packet: &[u8]) -> bool {
    let Some((_, tcp)) = parse_tcp_slices(packet) else {
        return false;
    };
    tcp.syn() && tcp.ack() && !tcp.fin() && !tcp.rst()
}

fn packet_destination(packet: &[u8]) -> Option<SocketAddr> {
    let (net, tcp) = parse_tcp_slices(packet)?;
    let ip = match net {
        NetSlice::Ipv4(ipv4) => IpAddr::V4(ipv4.header().destination_addr()),
        NetSlice::Ipv6(ipv6) => IpAddr::V6(ipv6.header().destination_addr()),
        NetSlice::Arp(_) => return None,
    };
    Some(SocketAddr::new(ip, tcp.destination_port()))
}

fn with_low_ttl(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    let (net, _) = parse_tcp_slices(packet)?;
    let mut modified = packet.to_vec();
    match net {
        NetSlice::Ipv4(_) => {
            let header_len = usize::from((modified[0] & 0x0F) * 4);
            if modified.len() < header_len || header_len <= IPV4_CHECKSUM_OFFSET + 1 {
                return None;
            }
            modified[IPV4_TTL_OFFSET] = ttl.max(1);
            recompute_ipv4_header_checksum(&mut modified[..header_len]);
        }
        NetSlice::Ipv6(_) => {
            if modified.len() <= IPV6_HOP_LIMIT_OFFSET {
                return None;
            }
            modified[IPV6_HOP_LIMIT_OFFSET] = ttl.max(1);
        }
        NetSlice::Arp(_) => return None,
    }
    Some(modified)
}

fn with_sequence_delta(packet: &[u8], sequence_delta: u32) -> Option<Vec<u8>> {
    let tcp_offset = tcp_header_offset(packet)?;
    let sequence_offset = tcp_offset.checked_add(TCP_SEQUENCE_OFFSET)?;
    let sequence_end = sequence_offset.checked_add(4)?;
    if packet.len() < sequence_end {
        return None;
    }

    let mut modified = packet.to_vec();
    let sequence = u32::from_be_bytes(modified[sequence_offset..sequence_end].try_into().ok()?);
    modified[sequence_offset..sequence_end].copy_from_slice(&sequence.wrapping_add(sequence_delta).to_be_bytes());
    recompute_tcp_checksum(&mut modified, tcp_offset)?;
    Some(modified)
}

fn tcp_header_offset(packet: &[u8]) -> Option<usize> {
    let (_, tcp) = parse_tcp_slices(packet)?;
    let packet_start = packet.as_ptr() as usize;
    let tcp_start = tcp.slice().as_ptr() as usize;
    tcp_start.checked_sub(packet_start)
}

fn recompute_tcp_checksum(packet: &mut [u8], tcp_offset: usize) -> Option<()> {
    enum IpFamily {
        V4 { source: [u8; 4], destination: [u8; 4] },
        V6 { source: [u8; 16], destination: [u8; 16], payload_length: usize },
    }

    let (family, tcp_header_len) = {
        let (net, tcp) = parse_tcp_slices(packet)?;
        let family = match net {
            NetSlice::Ipv4(ipv4) => {
                IpFamily::V4 { source: ipv4.header().source(), destination: ipv4.header().destination() }
            }
            NetSlice::Ipv6(ipv6) => IpFamily::V6 {
                source: ipv6.header().source(),
                destination: ipv6.header().destination(),
                payload_length: usize::from(ipv6.header().payload_length()),
            },
            NetSlice::Arp(_) => return None,
        };
        (family, tcp.slice().len())
    };
    let tcp_len = packet.len().checked_sub(tcp_offset)?;
    let checksum_offset = tcp_offset.checked_add(TCP_CHECKSUM_OFFSET)?;
    let checksum_end = checksum_offset.checked_add(2)?;
    if packet.len() < checksum_end {
        return None;
    }

    packet[checksum_offset..checksum_end].fill(0);
    let tcp_header = TcpHeaderSlice::from_slice(&packet[tcp_offset..]).ok()?;
    let payload_offset = tcp_offset.checked_add(tcp_header_len)?;
    if packet.len() < payload_offset {
        return None;
    }
    let payload = &packet[payload_offset..];
    let checksum = match family {
        IpFamily::V4 { source, destination } => tcp_header.calc_checksum_ipv4_raw(source, destination, payload).ok()?,
        IpFamily::V6 { source, destination, payload_length } => {
            if tcp_len > payload_length {
                return None;
            }
            tcp_header.calc_checksum_ipv6_raw(source, destination, payload).ok()?
        }
    };
    packet[checksum_offset..checksum_end].copy_from_slice(&checksum.to_be_bytes());
    Some(())
}

fn recompute_ipv4_header_checksum(header: &mut [u8]) {
    header[IPV4_CHECKSUM_OFFSET..IPV4_CHECKSUM_OFFSET + 2].fill(0);
    let checksum = finalize_checksum(checksum_sum(header));
    header[IPV4_CHECKSUM_OFFSET..IPV4_CHECKSUM_OFFSET + 2].copy_from_slice(&checksum.to_be_bytes());
}

fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let (chunks, remainder) = bytes.as_chunks::<2>();
    for chunk in chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = remainder.first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::net::{Ipv4Addr, Ipv6Addr};

    use super::*;

    #[derive(Default)]
    struct RecordingInjector {
        packets: Vec<Vec<u8>>,
    }

    impl SynAckPacketInjector for RecordingInjector {
        fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()> {
            self.packets.push(packet.to_vec());
            Ok(())
        }
    }

    #[test]
    fn identifies_ipv4_synack_without_fin_or_rst() {
        assert!(is_synack(&build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x12,
            sequence: 100,
            acknowledgment: 1,
        })));
        assert!(!is_synack(&build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x10,
            sequence: 100,
            acknowledgment: 1,
        })));
        assert!(!is_synack(&build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x16,
            sequence: 100,
            acknowledgment: 1,
        })));
    }

    #[test]
    fn identifies_ipv6_synack() {
        let packet = build_ipv6_tcp_packet(Ipv6Addr::LOCALHOST, Ipv6Addr::UNSPECIFIED, 0x12, 100, 1);

        assert!(is_synack(&packet));
    }

    #[test]
    fn disabled_interceptor_forwards_by_doing_nothing() {
        let packet = build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x12,
            sequence: 100,
            acknowledgment: 1,
        });
        let injector = RecordingInjector::default();
        let mut interceptor = TunIngressInterceptor::disabled(injector);

        interceptor.handle_packet(&packet);

        assert!(interceptor.injector.packets.is_empty());
    }

    #[test]
    fn synack_low_ttl_injects_low_ttl_copy_only() {
        let packet = build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x12,
            sequence: 100,
            acknowledgment: 1,
        });
        let mut interceptor =
            TunIngressInterceptor::with_strategy(SynAckStrategy::low_ttl(5), RecordingInjector::default());

        interceptor.handle_packet(&packet);

        assert_eq!(interceptor.injector.packets.len(), 1);
        assert_eq!(interceptor.injector.packets[0][IPV4_TTL_OFFSET], 5);
        assert_eq!(packet[IPV4_TTL_OFFSET], 64);
    }

    #[test]
    fn synack_split_injects_fake_then_real_with_different_sequence() {
        let packet = build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x12,
            sequence: 100,
            acknowledgment: 1,
        });
        let mut interceptor =
            TunIngressInterceptor::with_strategy(SynAckStrategy::split(), RecordingInjector::default());

        interceptor.handle_packet(&packet);

        assert_eq!(interceptor.injector.packets.len(), 2);
        assert_eq!(
            ipv4_tcp_sequence(&interceptor.injector.packets[0]),
            100_u32.wrapping_add(DEFAULT_SYNACK_SPLIT_SEQUENCE_DELTA)
        );
        assert_eq!(ipv4_tcp_sequence(&interceptor.injector.packets[1]), 100);
        assert_ne!(interceptor.injector.packets[0], interceptor.injector.packets[1]);
    }

    #[test]
    fn strategy_loads_from_yaml_chain() {
        let yaml = "version: 1\nstrategies:\n  - id: handshake\n    steps:\n      - type: synack\n        ttl: 7\n";

        assert_eq!(SynAckStrategy::from_yaml(Some(yaml)), Some(SynAckStrategy::LowTtl { ttl: 7 }));
    }

    #[test]
    fn raw_injector_without_protect_path_reports_unsupported() {
        let packet = build_ipv4_tcp_packet(TcpPacketSpec {
            src_ip: Ipv4Addr::new(203, 0, 113, 10),
            dst_ip: Ipv4Addr::new(10, 0, 0, 2),
            flags: 0x12,
            sequence: 100,
            acknowledgment: 1,
        });
        let mut injector = RawSynAckPacketInjector::new(None);

        let error = injector.inject_packet(&packet).expect_err("missing protect path should fail");

        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    struct TcpPacketSpec {
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
        flags: u8,
        sequence: u32,
        acknowledgment: u32,
    }

    fn build_ipv4_tcp_packet(spec: TcpPacketSpec) -> Vec<u8> {
        let mut packet = vec![0u8; 40];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&40u16.to_be_bytes());
        packet[8] = 64;
        packet[9] = 6;
        packet[12..16].copy_from_slice(&spec.src_ip.octets());
        packet[16..20].copy_from_slice(&spec.dst_ip.octets());
        packet[20..22].copy_from_slice(&443u16.to_be_bytes());
        packet[22..24].copy_from_slice(&49152u16.to_be_bytes());
        packet[24..28].copy_from_slice(&spec.sequence.to_be_bytes());
        packet[28..32].copy_from_slice(&spec.acknowledgment.to_be_bytes());
        packet[32] = 0x50;
        packet[33] = spec.flags;
        packet[34..36].copy_from_slice(&65535u16.to_be_bytes());
        recompute_ipv4_header_checksum(&mut packet[..20]);
        recompute_tcp_checksum(&mut packet, 20).expect("tcp checksum");
        packet
    }

    fn build_ipv6_tcp_packet(
        src_ip: Ipv6Addr,
        dst_ip: Ipv6Addr,
        flags: u8,
        sequence: u32,
        acknowledgment: u32,
    ) -> Vec<u8> {
        let mut packet = vec![0u8; 60];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&20u16.to_be_bytes());
        packet[6] = 6;
        packet[7] = 64;
        packet[8..24].copy_from_slice(&src_ip.octets());
        packet[24..40].copy_from_slice(&dst_ip.octets());
        packet[40..42].copy_from_slice(&443u16.to_be_bytes());
        packet[42..44].copy_from_slice(&49152u16.to_be_bytes());
        packet[44..48].copy_from_slice(&sequence.to_be_bytes());
        packet[48..52].copy_from_slice(&acknowledgment.to_be_bytes());
        packet[52] = 0x50;
        packet[53] = flags;
        packet[54..56].copy_from_slice(&65535u16.to_be_bytes());
        recompute_tcp_checksum(&mut packet, 40).expect("tcp checksum");
        packet
    }

    fn ipv4_tcp_sequence(packet: &[u8]) -> u32 {
        u32::from_be_bytes(packet[24..28].try_into().expect("sequence bytes"))
    }
}
