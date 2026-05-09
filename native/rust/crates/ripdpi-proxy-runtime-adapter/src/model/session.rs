use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use super::config::{ipv6_enabled, name_resolution_enabled, runtime_buffer_size, should_cache_udp_host, RuntimeConfig};

pub use ripdpi_session::*;

pub fn new_session_state() -> SessionState {
    SessionState::default()
}

pub fn observe_inbound_payload(session: &mut SessionState, payload: &[u8]) {
    session.observe_inbound(payload);
}

pub fn observe_outbound_payload(session: &mut SessionState, payload: &[u8]) -> OutboundProgress {
    session.observe_outbound(payload)
}

pub fn observe_datagram_outbound_payload(session: &mut SessionState, payload: &[u8]) -> OutboundProgress {
    session.observe_datagram_outbound(payload)
}

pub fn has_inbound_payload(session: &SessionState) -> bool {
    session.recv_count > 0
}

pub fn observe_first_response_payload(session: &mut SessionState, payload: &[u8]) -> bool {
    observe_inbound_payload(session, payload);
    has_inbound_payload(session)
}

pub fn observe_retry_response_payload(session: &mut SessionState, payload: &[u8]) {
    observe_inbound_payload(session, payload);
}

pub fn outbound_payload_count_this_round(session: &SessionState) -> usize {
    session.sent_this_round
}

pub struct OutboundPayloadInfo {
    pub host: Option<String>,
    pub is_tls: bool,
}

#[derive(Clone)]
pub struct FirstOutboundPayloadPolicy {
    pub buffer_size: usize,
    config: RuntimeConfig,
}

pub struct UdpPayloadInfo {
    pub host: Option<String>,
    pub cache_host: bool,
}

#[derive(Clone)]
pub struct PayloadHostExtractor {
    config: RuntimeConfig,
}

pub fn payload_host_extractor(config: &RuntimeConfig) -> PayloadHostExtractor {
    PayloadHostExtractor { config: config.clone() }
}

#[derive(Clone)]
pub struct UdpPayloadClassifier {
    config: RuntimeConfig,
}

pub fn udp_payload_classifier(config: &RuntimeConfig) -> UdpPayloadClassifier {
    UdpPayloadClassifier { config: config.clone() }
}

#[derive(Clone)]
pub struct UdpPacketParser {
    config: RuntimeConfig,
}

pub fn udp_packet_parser(config: &RuntimeConfig) -> UdpPacketParser {
    UdpPacketParser { config: config.clone() }
}

pub fn first_outbound_payload_policy(config: &RuntimeConfig) -> FirstOutboundPayloadPolicy {
    FirstOutboundPayloadPolicy { buffer_size: runtime_buffer_size(config), config: config.clone() }
}

pub fn classify_first_outbound_payload(policy: &FirstOutboundPayloadPolicy, payload: &[u8]) -> OutboundPayloadInfo {
    classify_outbound_payload(&policy.config, payload)
}

pub fn classify_outbound_payload(config: &RuntimeConfig, payload: &[u8]) -> OutboundPayloadInfo {
    OutboundPayloadInfo {
        host: extract_payload_host(config, payload),
        is_tls: ripdpi_runtime_decision_ports::is_tls_client_hello_payload(payload),
    }
}

pub fn extract_payload_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    ripdpi_runtime_decision_ports::extract_host(config, payload)
}

pub fn extract_payload_host_with(extractor: &PayloadHostExtractor, payload: &[u8]) -> Option<String> {
    extract_payload_host(&extractor.config, payload)
}

pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
    ripdpi_runtime_decision_ports::is_tls_client_hello_payload(payload)
}

pub fn classify_udp_payload(config: &RuntimeConfig, payload: &[u8]) -> UdpPayloadInfo {
    let host_info = ripdpi_runtime_decision_ports::extract_host_info(config, payload);
    UdpPayloadInfo {
        host: host_info.as_ref().map(|value| value.host.clone()),
        cache_host: should_cache_udp_host(config, host_info.as_ref()),
    }
}

pub fn classify_udp_payload_with(classifier: &UdpPayloadClassifier, payload: &[u8]) -> UdpPayloadInfo {
    classify_udp_payload(&classifier.config, payload)
}

pub fn parse_socks5_udp_packet<'a>(
    packet: &'a [u8],
    config: &RuntimeConfig,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    if packet.len() < 4 || packet[2] != 0 {
        return None;
    }
    let atyp = packet[3];
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !ipv6_enabled(config) {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
        }
        S_ATP_ID => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !name_resolution_enabled(config) {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let resolved = resolve_name(host, SocketType::Datagram)?;
            Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
        }
        _ => None,
    }
}

pub fn parse_socks5_udp_packet_with<'a>(
    parser: &UdpPacketParser,
    packet: &'a [u8],
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    parse_socks5_udp_packet(packet, &parser.config, resolve_name)
}

pub fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0, 0, 0];
    match sender {
        SocketAddr::V4(addr) => {
            packet.push(S_ATP_I4);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            packet.push(S_ATP_I6);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    packet
}

pub fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

pub fn read_upstream_socks_reply(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    reader.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_ID => {
            let mut len = [0u8; 1];
            reader.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

pub fn parse_shadowsocks_target(
    packet: &[u8],
    policy: super::config::ShadowsocksTargetPolicy,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => parse_ipv4_target(packet),
        S_ATP_I6 => parse_ipv6_target(packet, policy.ipv6_enabled),
        S_ATP_ID => parse_domain_target(packet, policy.resolve_enabled, &mut resolve_name),
        _ => None,
    }
}

fn parse_ipv4_target(packet: &[u8]) -> Option<(SocketAddr, usize)> {
    if packet.len() < 7 {
        return None;
    }

    let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
    let port = u16::from_be_bytes([packet[5], packet[6]]);
    Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
}

fn parse_ipv6_target(packet: &[u8], ipv6_enabled: bool) -> Option<(SocketAddr, usize)> {
    if packet.len() < 19 || !ipv6_enabled {
        return None;
    }

    let mut raw = [0u8; 16];
    raw.copy_from_slice(&packet[1..17]);
    let port = u16::from_be_bytes([packet[17], packet[18]]);
    Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
}

fn parse_domain_target(
    packet: &[u8],
    resolve_enabled: bool,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let len = *packet.get(1)? as usize;
    if packet.len() < 2 + len + 2 || !resolve_enabled {
        return None;
    }

    let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
    let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
    let resolved = resolve_name(host, SocketType::Stream)?;
    Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_outbound_payload_policy_applies_runtime_buffer_floor() {
        let mut config = RuntimeConfig::default();
        config.network.buffer_size = 512;
        let policy = first_outbound_payload_policy(&config);

        assert_eq!(policy.buffer_size, 16_384);
        let info = classify_first_outbound_payload(&policy, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
        assert_eq!(info.host.as_deref(), Some("example.com"));
        assert!(!info.is_tls);
    }

    #[test]
    fn payload_host_extractor_preserves_host_parsing() {
        let config = RuntimeConfig::default();
        let extractor = payload_host_extractor(&config);

        let host = extract_payload_host_with(&extractor, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");

        assert_eq!(host.as_deref(), Some("example.com"));
    }

    #[test]
    fn udp_payload_classifier_preserves_host_cache_policy() {
        let mut config = RuntimeConfig::default();
        config.quic.initial_mode = super::super::config::QuicInitialMode::RouteAndCache;
        let classifier = udp_payload_classifier(&config);

        let info = classify_udp_payload_with(&classifier, b"\xc3\x00\x00\x01\x08\x00\x00\x00\x00\x00");

        assert!(info.host.is_none());
        assert!(!info.cache_host);
    }

    #[test]
    fn udp_packet_parser_preserves_socks5_domain_resolution() {
        let config = RuntimeConfig::default();
        let parser = udp_packet_parser(&config);
        let packet = [0, 0, 0, S_ATP_ID, 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0x01, 0xbb, b'x'];

        let parsed = parse_socks5_udp_packet_with(&parser, &packet, |host, socket_type| {
            assert_eq!(host, "example");
            assert_eq!(socket_type, SocketType::Datagram);
            Some(SocketAddr::from(([203, 0, 113, 7], 0)))
        });

        assert_eq!(parsed, Some((SocketAddr::from(([203, 0, 113, 7], 443)), &b"x"[..])));
    }
}
