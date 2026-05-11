use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_strategy_config::{
    LoadedStrategy, LoadedStrategyConfig, OnFail, ProtocolName, StepType, StrategyMatch, StrategyStep,
};
use ripdpi_strategy_ipv6::{apply_ipv6_ext_header, Ipv6ExtType};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    L7Protocol, RuntimeCapability, StrategyContext, StrategyVerdict,
};
use tracing::{debug, warn};

const IPV4_MIN_HEADER_LEN: usize = 20;
const IPV6_HEADER_LEN: usize = 40;
const TCP_PROTO: u8 = 6;
const UDP_PROTO: u8 = 17;
const IPV6_HOP_BY_HOP: u8 = 0;
const IPV6_ROUTING: u8 = 43;
const IPV6_FRAGMENT: u8 = 44;
const IPV6_DESTINATION_OPTIONS: u8 = 60;

pub(in crate::io_loop) trait TunPacketInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()>;
}

pub(in crate::io_loop) trait TunEgressPacketHandler: Send {
    fn handle_packet(&mut self, packet: &[u8]) -> bool;
}

pub(in crate::io_loop) struct RawTunPacketInjector {
    protect_path: Option<String>,
}

impl RawTunPacketInjector {
    pub(in crate::io_loop) fn new(protect_path: Option<String>) -> Self {
        Self { protect_path }
    }
}

impl TunPacketInjector for RawTunPacketInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()> {
        let target = packet_destination(packet)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "packet has no transport destination"))?;
        ripdpi_runtime_platform::experimental::send_raw_ip_packet(target, packet, self.protect_path.as_deref())
    }
}

pub(in crate::io_loop) struct TunEgressInterceptor<I> {
    rules: Vec<EgressRule>,
    injector: I,
}

impl<I: TunPacketInjector> TunEgressInterceptor<I> {
    pub(in crate::io_loop) fn new(strategy_yaml: Option<&str>, injector: I) -> Self {
        let rules = strategy_yaml.map(parse_rules).unwrap_or_default();
        Self { rules, injector }
    }

    pub(in crate::io_loop) fn handle_packet(&mut self, packet: &[u8]) -> bool {
        if self.rules.is_empty() {
            return false;
        }
        let Some(meta) = PacketMeta::parse(packet) else {
            return false;
        };

        for rule in &self.rules {
            if !rule.matcher.matches(meta) {
                continue;
            }
            if rule.action.apply(packet, meta, &mut self.injector) {
                return true;
            }
        }
        false
    }
}

impl<I: TunPacketInjector + Send> TunEgressPacketHandler for TunEgressInterceptor<I> {
    fn handle_packet(&mut self, packet: &[u8]) -> bool {
        TunEgressInterceptor::handle_packet(self, packet)
    }
}

fn parse_rules(strategy_yaml: &str) -> Vec<EgressRule> {
    match ripdpi_strategy_config::parse_yaml_str(strategy_yaml, ".") {
        Ok(config) => config.strategies.iter().flat_map(rules_for_strategy).collect(),
        Err(error) => {
            warn!("failed to parse strategy YAML for TUN egress interception: {error}");
            Vec::new()
        }
    }
}

fn rules_for_strategy(strategy: &LoadedStrategy) -> Vec<EgressRule> {
    strategy
        .steps
        .iter()
        .filter_map(|step| {
            EgressRuleAction::from_step(step)
                .map(|action| EgressRule { matcher: PacketMatcher::from_strategy(strategy), action })
        })
        .collect()
}

struct EgressRule {
    matcher: PacketMatcher,
    action: EgressRuleAction,
}

enum EgressRuleAction {
    Direct(EgressAction),
    Strategy { registry: StrategyRegistry, forward_original: bool },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EgressAction {
    Fake { ttl: u8 },
    UdpLen { delta: i16 },
    Ipv6Ext { ext_type: Ipv6ExtType },
}

impl EgressAction {
    fn from_step(step: &StrategyStep) -> Option<Self> {
        match step.kind {
            StepType::Fake => {
                let ttl = step.ttl.unwrap_or(5).max(1);
                Some(Self::Fake { ttl })
            }
            StepType::Udplen => {
                let delta = step.delta.unwrap_or(4).clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
                Some(Self::UdpLen { delta })
            }
            StepType::Ipv6Ext => {
                let ext_type = step.ext_type.as_deref().and_then(Ipv6ExtType::parse).unwrap_or_default();
                Some(Self::Ipv6Ext { ext_type })
            }
            _ => None,
        }
    }

    fn apply(self, packet: &[u8]) -> Option<Vec<u8>> {
        match self {
            Self::Fake { ttl } => low_ttl_tcp_copy(packet, ttl),
            Self::UdpLen { delta } => ripdpi_strategy_udp::apply_udplen(packet, delta),
            Self::Ipv6Ext { ext_type } => apply_ipv6_ext_header(packet, ext_type),
        }
    }
}

impl EgressRuleAction {
    fn from_step(step: &StrategyStep) -> Option<Self> {
        if step.kind == StepType::Lua {
            return Self::lua_strategy(step);
        }
        EgressAction::from_step(step).map(Self::Direct)
    }

    fn lua_strategy(step: &StrategyStep) -> Option<Self> {
        let config = LoadedStrategyConfig {
            version: 1,
            strategies: vec![LoadedStrategy {
                id: step.function.clone().unwrap_or_else(|| "lua".to_owned()),
                matcher: StrategyMatch::default(),
                steps: vec![step.clone()],
                on_fail: OnFail::default(),
            }],
        };
        match StrategyRegistry::from_loaded_config(&config) {
            Ok(registry) => Some(Self::Strategy { registry, forward_original: step.forward_original.unwrap_or(false) }),
            Err(error) => {
                warn!("failed to materialize Lua TUN egress strategy: {error}");
                None
            }
        }
    }

    fn apply<I: TunPacketInjector>(&self, packet: &[u8], meta: PacketMeta, injector: &mut I) -> bool {
        match self {
            Self::Direct(action) => inject_direct_action(*action, packet, injector),
            Self::Strategy { registry, forward_original } => {
                execute_registry_action(registry, *forward_original, packet, meta, injector)
            }
        }
    }
}

fn inject_direct_action<I: TunPacketInjector>(action: EgressAction, packet: &[u8], injector: &mut I) -> bool {
    let Some(transformed) = action.apply(packet) else {
        return false;
    };
    if transformed == packet {
        return false;
    }
    match injector.inject_packet(&transformed) {
        Ok(()) => false,
        Err(error) => {
            debug!("TUN egress strategy injection failed; forwarding original packet: {error}");
            false
        }
    }
}

fn execute_registry_action<I: TunPacketInjector>(
    registry: &StrategyRegistry,
    forward_original: bool,
    packet: &[u8],
    meta: PacketMeta,
    injector: &mut I,
) -> bool {
    let dissect = Dissect {
        proto: L7Protocol::Unknown,
        src_port: meta.src_port,
        dst_port: meta.dst_port,
        is_ipv6: meta.is_ipv6,
        ..Dissect::default()
    };
    let conn = ConnectionState { packet_count: 1 };
    let caps = Capabilities { tier: CapabilityTier::Tier3, available: vec![RuntimeCapability::VpnMode] };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(meta.flow_id()),
        payload: packet,
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    let verdict = registry.execute(&ctx, &mut plan);
    if verdict == StrategyVerdict::Drop {
        return true;
    }

    let mut injected = false;
    for action in plan.actions {
        if let DesyncAction::RawSend(output) = action {
            if output.is_empty() || output == packet {
                continue;
            }
            match injector.inject_packet(&output) {
                Ok(()) => injected = true,
                Err(error) => debug!("Lua TUN egress rawsend failed; forwarding original packet: {error}"),
            }
        }
    }
    injected && !forward_original
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PacketMatcher {
    proto: Vec<ProtocolName>,
    ports: Vec<u16>,
}

impl PacketMatcher {
    fn from_strategy(strategy: &LoadedStrategy) -> Self {
        Self { proto: strategy.matcher.proto.clone(), ports: strategy.matcher.port.clone() }
    }

    fn matches(&self, meta: PacketMeta) -> bool {
        self.matches_port(meta) && self.matches_proto(meta)
    }

    fn matches_port(&self, meta: PacketMeta) -> bool {
        self.ports.is_empty() || self.ports.iter().any(|port| *port == meta.src_port || *port == meta.dst_port)
    }

    fn matches_proto(&self, meta: PacketMeta) -> bool {
        self.proto.is_empty()
            || self.proto.iter().any(|proto| {
                matches!(
                    (proto, meta.transport),
                    (ProtocolName::Any, _)
                        | (
                            ProtocolName::Quic
                                | ProtocolName::Dtls
                                | ProtocolName::Stun
                                | ProtocolName::Dht
                                | ProtocolName::Wireguard,
                            Transport::Udp,
                        )
                        | (ProtocolName::Tls | ProtocolName::Http | ProtocolName::Mtproto, Transport::Tcp)
                )
            })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct PacketMeta {
    transport: Transport,
    is_ipv6: bool,
    src_ip: IpAddr,
    dst_ip: IpAddr,
    src_port: u16,
    dst_port: u16,
}

impl PacketMeta {
    fn parse(packet: &[u8]) -> Option<Self> {
        transport_endpoint(packet).map(|endpoint| Self {
            transport: endpoint.transport,
            is_ipv6: endpoint.is_ipv6,
            src_ip: endpoint.src_ip,
            dst_ip: endpoint.dst_ip,
            src_port: endpoint.src_port,
            dst_port: endpoint.dst_port,
        })
    }

    fn flow_id(self) -> u64 {
        let ports = (u64::from(self.src_port) << 16) | u64::from(self.dst_port);
        ports ^ ip_hash(self.src_ip).rotate_left(13) ^ ip_hash(self.dst_ip).rotate_left(29)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Transport {
    Tcp,
    Udp,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TransportEndpoint {
    transport: Transport,
    is_ipv6: bool,
    src_ip: IpAddr,
    dst_ip: IpAddr,
    src_port: u16,
    dst_port: u16,
}

fn ip_hash(ip: IpAddr) -> u64 {
    match ip {
        IpAddr::V4(value) => u64::from(u32::from(value)),
        IpAddr::V6(value) => {
            let octets = value.octets();
            u64::from_be_bytes(octets[..8].try_into().unwrap_or([0; 8]))
                ^ u64::from_be_bytes(octets[8..].try_into().unwrap_or([0; 8]))
        }
    }
}

fn packet_destination(packet: &[u8]) -> Option<SocketAddr> {
    let endpoint = transport_endpoint(packet)?;
    Some(SocketAddr::new(endpoint.dst_ip, endpoint.dst_port))
}

fn transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    let version = packet.first()? >> 4;
    match version {
        4 => ipv4_transport_endpoint(packet),
        6 => ipv6_transport_endpoint(packet),
        _ => None,
    }
}

fn ipv4_transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    if packet.len() < IPV4_MIN_HEADER_LEN {
        return None;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    if ihl < IPV4_MIN_HEADER_LEN || packet.len() < ihl + 4 {
        return None;
    }
    let transport = match packet[9] {
        TCP_PROTO => Transport::Tcp,
        UDP_PROTO => Transport::Udp,
        _ => return None,
    };
    Some(TransportEndpoint {
        transport,
        is_ipv6: false,
        src_ip: IpAddr::V4(Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15])),
        dst_ip: IpAddr::V4(Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19])),
        src_port: u16::from_be_bytes([packet[ihl], packet[ihl + 1]]),
        dst_port: u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]),
    })
}

fn ipv6_transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    if packet.len() < IPV6_HEADER_LEN {
        return None;
    }
    let src = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[8..24]).ok()?);
    let dst = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[24..40]).ok()?);
    let mut next_header = packet[6];
    let mut offset = IPV6_HEADER_LEN;
    for _ in 0..8 {
        let transport = match next_header {
            TCP_PROTO => Transport::Tcp,
            UDP_PROTO => Transport::Udp,
            IPV6_HOP_BY_HOP | IPV6_ROUTING | IPV6_DESTINATION_OPTIONS => {
                if packet.len() < offset + 2 {
                    return None;
                }
                next_header = packet[offset];
                let header_len = (usize::from(packet[offset + 1]) + 1) * 8;
                offset = offset.checked_add(header_len)?;
                continue;
            }
            IPV6_FRAGMENT => {
                if packet.len() < offset + 8 {
                    return None;
                }
                next_header = packet[offset];
                offset = offset.checked_add(8)?;
                continue;
            }
            _ => return None,
        };
        if packet.len() < offset + 4 {
            return None;
        }
        return Some(TransportEndpoint {
            transport,
            is_ipv6: true,
            src_ip: IpAddr::V6(src),
            dst_ip: IpAddr::V6(dst),
            src_port: u16::from_be_bytes([packet[offset], packet[offset + 1]]),
            dst_port: u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]),
        });
    }
    None
}

fn low_ttl_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    let version = packet.first()? >> 4;
    match version {
        4 => low_ttl_ipv4_tcp_copy(packet, ttl),
        6 => low_ttl_ipv6_tcp_copy(packet, ttl),
        _ => None,
    }
}

fn low_ttl_ipv4_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    if packet.len() < IPV4_MIN_HEADER_LEN || packet[9] != TCP_PROTO {
        return None;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    if ihl < IPV4_MIN_HEADER_LEN || packet.len() < ihl {
        return None;
    }

    let mut modified = packet.to_vec();
    modified[8] = ttl.max(1);
    recompute_ipv4_header_checksum(&mut modified[..ihl]);
    Some(modified)
}

fn low_ttl_ipv6_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    if packet.len() < IPV6_HEADER_LEN || packet[6] != TCP_PROTO {
        return None;
    }

    let mut modified = packet.to_vec();
    modified[7] = ttl.max(1);
    Some(modified)
}

fn recompute_ipv4_header_checksum(header: &mut [u8]) {
    header[10] = 0;
    header[11] = 0;
    let checksum = ipv4_checksum(header);
    header[10..12].copy_from_slice(&checksum.to_be_bytes());
}

fn ipv4_checksum(bytes: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = chunks.remainder().first() {
        sum += u32::from(*last) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use std::io;

    use super::*;

    #[test]
    fn udplen_rule_injects_modified_udp_packet_and_forwards_original() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let yaml = r#"
version: 1
strategies:
  - id: quic-udp
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: udplen
        delta: 4
"#;
        let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

        assert!(!interceptor.handle_packet(&packet));

        let injected = &interceptor.injector.packets[0];
        let udp_len_offset = IPV4_MIN_HEADER_LEN + 4;
        assert_eq!(u16::from_be_bytes([injected[udp_len_offset], injected[udp_len_offset + 1]]), 15);
        assert_eq!(&injected[2..4], &packet[2..4], "IP total length must stay unchanged");
    }

    #[test]
    fn fake_rule_injects_low_ttl_tcp_copy_and_forwards_original() {
        let packet = ipv4_tcp_packet(49152, 443, b"GET / HTTP/1.1\r\nHost: example.org\r\n\r\n");
        let yaml = r#"
version: 1
strategies:
  - id: fake-tcp
    match:
      proto: [tls]
      port: [443]
    steps:
      - type: fake
        ttl: 5
"#;
        let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

        assert!(!interceptor.handle_packet(&packet));

        let injected = &interceptor.injector.packets[0];
        assert_eq!(injected[8], 5);
        assert_eq!(packet[8], 64, "original packet must remain untouched for normal TUN forwarding");
        assert_ne!(&injected[10..12], &packet[10..12], "IPv4 checksum should be refreshed after TTL change");
    }

    #[test]
    fn ipv6_ext_rule_injects_modified_tcp_packet_and_forwards_original() {
        let packet = ipv6_tcp_packet();
        let yaml = r#"
version: 1
strategies:
  - id: ipv6-ext
    match:
      proto: [tls]
      port: [443]
    steps:
      - type: ipv6Ext
        ext_type: destopts
"#;
        let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

        assert!(!interceptor.handle_packet(&packet));

        let injected = &interceptor.injector.packets[0];
        assert_eq!(injected[6], 60);
        assert_eq!(u16::from_be_bytes([injected[4], injected[5]]), 28);
        assert_eq!(packet_destination(injected), Some(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443)));
    }

    #[test]
    fn failed_injection_forwards_original_normally() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let yaml = r#"
version: 1
strategies:
  - id: quic-udp
    steps:
      - type: udplen
"#;
        let mut interceptor = TunEgressInterceptor::new(Some(yaml), FailingInjector);

        assert!(!interceptor.handle_packet(&packet));
    }

    #[test]
    fn lua_rawsend_injects_packet_and_consumes_original_by_default() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let script = write_lua_script(
            "lua-egress-consume",
            r#"
function candidate(desync)
    desync.rawsend("lua-raw")
    return VERDICT_MODIFY
end
"#,
        );
        let yaml = format!(
            r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
            script.display()
        );
        let mut interceptor = TunEgressInterceptor::new(Some(&yaml), RecordingInjector::default());

        assert!(interceptor.handle_packet(&packet));
        assert_eq!(interceptor.injector.packets, [b"lua-raw".to_vec()]);
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn lua_rawsend_can_forward_original() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let script = write_lua_script(
            "lua-egress-forward",
            r#"
function candidate(desync)
    desync.rawsend("lua-sidecar")
    return VERDICT_MODIFY
end
"#,
        );
        let yaml = format!(
            r#"
version: 1
strategies:
  - id: lua-egress
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
        forward_original: true
"#,
            script.display()
        );
        let mut interceptor = TunEgressInterceptor::new(Some(&yaml), RecordingInjector::default());

        assert!(!interceptor.handle_packet(&packet));
        assert_eq!(interceptor.injector.packets, [b"lua-sidecar".to_vec()]);
        let _ = std::fs::remove_file(script);
    }

    #[derive(Default)]
    struct RecordingInjector {
        packets: Vec<Vec<u8>>,
    }

    impl TunPacketInjector for RecordingInjector {
        fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()> {
            self.packets.push(packet.to_vec());
            Ok(())
        }
    }

    struct FailingInjector;

    impl TunPacketInjector for FailingInjector {
        fn inject_packet(&mut self, _packet: &[u8]) -> io::Result<()> {
            Err(io::Error::other("boom"))
        }
    }

    fn write_lua_script(name: &str, contents: &str) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("{name}-{}.lua", std::process::id()));
        std::fs::write(&path, contents).expect("write Lua script");
        path
    }

    fn ipv4_udp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let total_len = IPV4_MIN_HEADER_LEN + 8 + payload.len();
        let mut packet = vec![0u8; total_len];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = UDP_PROTO;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
        packet[20..22].copy_from_slice(&src_port.to_be_bytes());
        packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
        packet[24..26].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
        packet[28..].copy_from_slice(payload);
        packet
    }

    fn ipv4_tcp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let total_len = IPV4_MIN_HEADER_LEN + 20 + payload.len();
        let mut packet = vec![0u8; total_len];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = TCP_PROTO;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
        packet[20..22].copy_from_slice(&src_port.to_be_bytes());
        packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
        packet[32] = 0x50;
        packet[33] = 0x18;
        packet[34..36].copy_from_slice(&65535u16.to_be_bytes());
        packet[40..].copy_from_slice(payload);
        recompute_ipv4_header_checksum(&mut packet[..IPV4_MIN_HEADER_LEN]);
        packet
    }

    fn ipv6_tcp_packet() -> Vec<u8> {
        let mut packet = vec![0u8; IPV6_HEADER_LEN + 20];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&20u16.to_be_bytes());
        packet[6] = TCP_PROTO;
        packet[7] = 64;
        packet[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        packet[40..42].copy_from_slice(&49152u16.to_be_bytes());
        packet[42..44].copy_from_slice(&443u16.to_be_bytes());
        packet[52] = 0x50;
        packet[53] = 0x18;
        packet[54..56].copy_from_slice(&65535u16.to_be_bytes());
        packet
    }
}
