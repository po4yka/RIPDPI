use std::collections::HashMap;
use std::io;

#[cfg(test)]
use std::net::SocketAddr;

use ripdpi_packets::{
    http_marker_info, parse_quic_initial, parse_quic_initial_layout, parse_tls, second_level_domain_span,
    tls_marker_info, QuicInitialLayout,
};
use ripdpi_strategy_config::{
    LoadedStrategy, LoadedStrategyConfig, OnFail, ProtocolName, StepType, StrategyMatch, StrategyStep,
};
use ripdpi_strategy_ipv6::{apply_ipv6_ext_header, Ipv6ExtType};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    HttpDissect, L7Protocol, MarkerName, QuicDissect, RuntimeCapability, StrategyContext, StrategyVerdict, TlsDissect,
};
use tracing::{debug, warn};

mod packet;

use self::packet::{
    low_ttl_tcp_copy, packet_destination, packet_with_payload, set_packet_hop_limit, transport_endpoint, PacketMeta,
    Transport,
};

#[cfg(test)]
use self::packet::{recompute_ipv4_header_checksum, IPV4_MIN_HEADER_LEN, IPV6_HEADER_LEN, TCP_PROTO, UDP_PROTO};

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
    let payload = meta.payload(packet);
    let dissect = dissect_packet(meta, payload);
    let conn = ConnectionState { packet_count: 1 };
    let caps = Capabilities { tier: CapabilityTier::Tier3, available: vec![RuntimeCapability::VpnMode] };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(meta.flow_id()),
        payload,
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    let verdict = registry.execute(&ctx, &mut plan);
    let drop_original = match verdict {
        StrategyVerdict::Apply => false,
        StrategyVerdict::FallbackPlain => return false,
        StrategyVerdict::Drop => true,
    };

    let mut injected = false;
    let mut sequence_delta = 0u32;
    let mut ttl = None;
    for action in plan.actions {
        match action {
            DesyncAction::RawSend(output) | DesyncAction::Write(output) => {
                if inject_strategy_output(packet, meta, &output, sequence_delta, ttl, injector) {
                    injected = true;
                    sequence_delta = sequence_delta.wrapping_add(output.len() as u32);
                }
                ttl = None;
            }
            DesyncAction::Split { offset, disorder } => {
                if let Some((first, second)) = split_payload(payload, offset) {
                    if disorder {
                        injected |= inject_strategy_output(packet, meta, second, first.len() as u32, ttl, injector);
                        injected |= inject_strategy_output(packet, meta, first, 0, ttl, injector);
                    } else {
                        injected |= inject_strategy_output(packet, meta, first, 0, ttl, injector);
                        injected |= inject_strategy_output(packet, meta, second, first.len() as u32, ttl, injector);
                    }
                }
                ttl = None;
            }
            DesyncAction::SetTtl(next_ttl) => ttl = Some(next_ttl),
            DesyncAction::RestoreDefaultTtl => ttl = None,
            DesyncAction::WriteFake { ttl: fake_ttl, .. } => {
                if let Some(output) = low_ttl_tcp_copy(packet, fake_ttl.or(ttl).unwrap_or(5)) {
                    injected |= inject_strategy_output(packet, meta, &output, 0, None, injector);
                }
                ttl = None;
            }
            DesyncAction::UdpLen { delta } => {
                if let Some(output) = ripdpi_strategy_udp::apply_udplen(packet, delta) {
                    injected |= inject_strategy_output(packet, meta, &output, 0, None, injector);
                }
                ttl = None;
            }
            DesyncAction::SetWindowClamp(_) | DesyncAction::WriteUrgent { .. } | DesyncAction::SendFakeRst { .. } => {
                debug!("Lua TUN egress action is unsupported on raw TUN path; forwarding original packet");
            }
        }
    }
    drop_original || (injected && !forward_original)
}

fn dissect_packet(meta: PacketMeta, payload: &[u8]) -> Dissect {
    let mut dissect = Dissect {
        proto: classify_l7(meta.transport, payload),
        src_port: meta.src_port,
        dst_port: meta.dst_port,
        is_ipv6: meta.is_ipv6,
        markers: HashMap::new(),
    };
    populate_markers(&mut dissect, payload);
    dissect
}

fn classify_l7(transport: Transport, payload: &[u8]) -> L7Protocol {
    match transport {
        Transport::Tcp => {
            if let Some(host) = parse_tls(payload).map(|host| String::from_utf8_lossy(host).into_owned()) {
                L7Protocol::Tls(TlsDissect { sni: Some(host), is_client_hello: true })
            } else if let Some(markers) = http_marker_info(payload) {
                let host = String::from_utf8_lossy(&payload[markers.host_start..markers.host_end]).into_owned();
                L7Protocol::Http(HttpDissect { host: Some(host), is_request: true })
            } else {
                L7Protocol::Unknown
            }
        }
        Transport::Udp => parse_quic_initial(payload)
            .map_or(L7Protocol::Unknown, |quic| L7Protocol::Quic(QuicDissect { version: Some(quic.version) })),
    }
}

fn populate_markers(dissect: &mut Dissect, payload: &[u8]) {
    dissect.markers.insert(MarkerName::Data, 0);
    dissect.markers.insert(MarkerName::End, payload.len());
    match &dissect.proto {
        L7Protocol::Tls(_) => {
            if let Some(markers) = tls_marker_info(payload) {
                insert_host_markers(&mut dissect.markers, payload, markers.host_start, markers.host_end);
                dissect.markers.insert(MarkerName::ExtLen, markers.ext_len_start);
                dissect.markers.insert(MarkerName::SniExt, markers.sni_ext_start);
            }
        }
        L7Protocol::Http(_) => {
            if let Some(markers) = http_marker_info(payload) {
                dissect.markers.insert(MarkerName::HttpMethod, markers.method_start);
                insert_host_markers(&mut dissect.markers, payload, markers.host_start, markers.host_end);
            }
        }
        L7Protocol::Quic(_) => {
            if let Some(layout) = parse_quic_initial_layout(payload) {
                insert_quic_markers(&mut dissect.markers, &layout);
            }
        }
        L7Protocol::Any
        | L7Protocol::Unknown
        | L7Protocol::Known
        | L7Protocol::Dtls(_)
        | L7Protocol::WireGuard(_)
        | L7Protocol::Dht(_)
        | L7Protocol::Discord(_)
        | L7Protocol::Stun(_)
        | L7Protocol::Xmpp(_)
        | L7Protocol::Dns(_)
        | L7Protocol::Mtproto(_)
        | L7Protocol::BitTorrent(_)
        | L7Protocol::UtpBitTorrent(_) => {}
    }
}

fn insert_quic_markers(markers: &mut HashMap<MarkerName, usize>, layout: &QuicInitialLayout) {
    let tls_info = &layout.info.tls_info;
    insert_quic_host_markers(markers, layout, tls_info.host_start, tls_info.host_end);
    if let Some(ext_len) = quic_marker_start_offset(layout, tls_info.ext_len_start) {
        markers.insert(MarkerName::ExtLen, ext_len);
    }
    if let Some(sni_ext) = quic_marker_start_offset(layout, tls_info.sni_ext_start) {
        markers.insert(MarkerName::SniExt, sni_ext);
    }
}

fn insert_quic_host_markers(
    markers: &mut HashMap<MarkerName, usize>,
    layout: &QuicInitialLayout,
    host_start: usize,
    host_end: usize,
) {
    if let Some(host) = layout.info.client_hello.get(host_start..host_end) {
        if let Some(offset) = quic_marker_start_offset(layout, host_start) {
            markers.insert(MarkerName::Host, offset);
        }
        if let Some(offset) = quic_marker_end_offset(layout, host_end) {
            markers.insert(MarkerName::HostEnd, offset);
        }
        if let Some((sld_start, sld_end)) = second_level_domain_span(host) {
            let host_sld_start = host_start + sld_start;
            let host_sld_mid = host_start + sld_start + (sld_end - sld_start) / 2;
            let host_sld_end = host_start + sld_end;
            if let Some(offset) = quic_marker_start_offset(layout, host_sld_start) {
                markers.insert(MarkerName::HostSld, offset);
            }
            if let Some(offset) = quic_marker_start_offset(layout, host_sld_mid) {
                markers.insert(MarkerName::HostMidSld, offset);
            }
            if let Some(offset) = quic_marker_end_offset(layout, host_sld_end) {
                markers.insert(MarkerName::HostEndSld, offset);
            }
        }
    }
}

fn quic_marker_start_offset(layout: &QuicInitialLayout, crypto_offset: usize) -> Option<usize> {
    layout.crypto_frames.iter().find_map(|frame| {
        let frame_end = frame.crypto_offset.checked_add(frame.data_len)?;
        if crypto_offset < frame.crypto_offset || crypto_offset >= frame_end {
            return None;
        }
        layout
            .ciphertext_payload_offset
            .checked_add(frame.data_offset)?
            .checked_add(crypto_offset - frame.crypto_offset)
    })
}

fn quic_marker_end_offset(layout: &QuicInitialLayout, crypto_offset: usize) -> Option<usize> {
    if crypto_offset == 0 {
        return quic_marker_start_offset(layout, 0);
    }
    quic_marker_start_offset(layout, crypto_offset - 1)?.checked_add(1)
}

fn insert_host_markers(markers: &mut HashMap<MarkerName, usize>, payload: &[u8], host_start: usize, host_end: usize) {
    markers.insert(MarkerName::Host, host_start);
    markers.insert(MarkerName::HostEnd, host_end);
    if let Some((sld_start, sld_end)) = payload.get(host_start..host_end).and_then(second_level_domain_span) {
        markers.insert(MarkerName::HostSld, host_start + sld_start);
        markers.insert(MarkerName::HostMidSld, host_start + sld_start + (sld_end - sld_start) / 2);
        markers.insert(MarkerName::HostEndSld, host_start + sld_end);
    }
}

fn split_payload(payload: &[u8], offset: usize) -> Option<(&[u8], &[u8])> {
    if offset == 0 || offset >= payload.len() {
        return None;
    }
    Some(payload.split_at(offset))
}

fn inject_strategy_output<I: TunPacketInjector>(
    packet: &[u8],
    meta: PacketMeta,
    output: &[u8],
    sequence_delta: u32,
    ttl: Option<u8>,
    injector: &mut I,
) -> bool {
    if output.is_empty() {
        return false;
    }
    let injection = if transport_endpoint(output).is_some() {
        let mut packet = output.to_vec();
        if let Some(ttl) = ttl {
            if set_packet_hop_limit(&mut packet, ttl).is_none() {
                return false;
            }
        }
        packet
    } else {
        match packet_with_payload(packet, meta, output, sequence_delta, ttl) {
            Some(packet) => packet,
            None => return false,
        }
    };
    match injector.inject_packet(&injection) {
        Ok(()) => true,
        Err(error) => {
            debug!("Lua TUN egress injection failed; forwarding original packet: {error}");
            false
        }
    }
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

#[cfg(test)]
mod tests {
    use std::io;
    use std::net::{IpAddr, Ipv6Addr};

    use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial_layout, QUIC_V1_VERSION};

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
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-raw");
        assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
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
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-sidecar");
        assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn lua_rawsend_then_drop_injects_packet_and_consumes_original() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let script = write_lua_script(
            "lua-egress-drop",
            r#"
function candidate(desync)
    desync.rawsend("lua-drop-raw")
    return VERDICT_DROP
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
        assert_eq!(interceptor.injector.packets.len(), 1);
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-drop-raw");
        assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn lua_context_exposes_http_markers_to_tun_strategy() {
        let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let packet = ipv4_tcp_packet(49152, 80, payload);
        let script = write_lua_script(
            "lua-egress-http-markers",
            r#"
function candidate(desync)
    if desync.detect("http")
        and desync.dis.hostname == "example.com"
        and desync.pos("host")
        and desync.pos("endhost") then
        desync.set_ttl(9)
        desync.rawsend(desync.payload)
        return VERDICT_MODIFY
    end
    return VERDICT_PASS
end
"#,
        );
        let yaml = format!(
            r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [http]
      port: [80]
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
        assert_eq!(interceptor.injector.packets[0][8], 9);
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), payload);
        assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn quic_markers_are_raw_udp_payload_offsets() {
        let payload =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build QUIC initial");
        let layout = parse_quic_initial_layout(&payload).expect("parse QUIC layout");
        let packet = ipv4_udp_packet(49152, 443, &payload);
        let meta = PacketMeta::parse(&packet).expect("packet metadata");

        let dissect = dissect_packet(meta, meta.payload(&packet));

        let host = *dissect.markers.get(&MarkerName::Host).expect("host marker");
        let host_end = *dissect.markers.get(&MarkerName::HostEnd).expect("host end marker");
        let sni_ext = *dissect.markers.get(&MarkerName::SniExt).expect("sni extension marker");
        assert_eq!(host, expected_quic_marker_start(&layout, layout.info.tls_info.host_start));
        assert_eq!(host_end, expected_quic_marker_end(&layout, layout.info.tls_info.host_end));
        assert_eq!(sni_ext, expected_quic_marker_start(&layout, layout.info.tls_info.sni_ext_start));
        assert_ne!(host, layout.info.tls_info.host_start, "QUIC markers must not use reassembled TLS offsets");
        assert!(host < payload.len());
        assert!(host_end <= payload.len());
        assert!(sni_ext < payload.len());
    }

    #[test]
    fn lua_quic_split_uses_raw_udp_payload_marker_offset() {
        let payload =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build QUIC initial");
        let layout = parse_quic_initial_layout(&payload).expect("parse QUIC layout");
        let expected_host = expected_quic_marker_start(&layout, layout.info.tls_info.host_start);
        let packet = ipv4_udp_packet(49152, 443, &payload);
        let script = write_lua_script(
            "lua-egress-quic-markers",
            r#"
function candidate(desync)
    local host = desync.pos("host")
    local endhost = desync.pos("endhost")
    local sni_ext = desync.pos("sni_ext")
    if host and endhost and sni_ext then
        desync.split(host, false)
        return VERDICT_MODIFY
    end
    return VERDICT_PASS
end
"#,
        );
        let yaml = format!(
            r#"
version: 1
strategies:
  - id: lua-egress-quic-markers
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
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), &payload[..expected_host]);
        assert_eq!(packet_payload(&interceptor.injector.packets[1]), &payload[expected_host..]);
        assert_ne!(expected_host, layout.info.tls_info.host_start, "test must catch client-hello coordinate reuse");
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn lua_udplen_action_injects_valid_packet_before_consuming_original() {
        let packet = ipv4_udp_packet(443, 443, b"abc");
        let script = write_lua_script(
            "lua-egress-udplen",
            r#"
function candidate(desync)
    desync.udplen(4)
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

        let injected = &interceptor.injector.packets[0];
        let udp_len_offset = IPV4_MIN_HEADER_LEN + 4;
        assert_eq!(u16::from_be_bytes([injected[udp_len_offset], injected[udp_len_offset + 1]]), 15);
        assert!(packet_destination(injected).is_some());
        let _ = std::fs::remove_file(script);
    }

    #[test]
    fn lua_split_action_injects_valid_payload_packets_before_consuming_original() {
        let packet = ipv4_tcp_packet(49152, 443, b"abcdef");
        let script = write_lua_script(
            "lua-egress-split",
            r#"
function candidate(desync)
    desync.split(2, false)
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
"#,
            script.display()
        );
        let mut interceptor = TunEgressInterceptor::new(Some(&yaml), RecordingInjector::default());

        assert!(interceptor.handle_packet(&packet));
        assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"ab");
        assert_eq!(packet_payload(&interceptor.injector.packets[1]), b"cdef");
        assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
        assert!(packet_destination(&interceptor.injector.packets[1]).is_some());
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

    fn expected_quic_marker_start(layout: &QuicInitialLayout, crypto_offset: usize) -> usize {
        layout
            .crypto_frames
            .iter()
            .find_map(|frame| {
                let frame_end = frame.crypto_offset + frame.data_len;
                if crypto_offset < frame.crypto_offset || crypto_offset >= frame_end {
                    return None;
                }
                Some(layout.ciphertext_payload_offset + frame.data_offset + crypto_offset - frame.crypto_offset)
            })
            .expect("QUIC marker start must map into a CRYPTO frame")
    }

    fn expected_quic_marker_end(layout: &QuicInitialLayout, crypto_offset: usize) -> usize {
        expected_quic_marker_start(layout, crypto_offset - 1) + 1
    }

    fn packet_payload(packet: &[u8]) -> &[u8] {
        let meta = PacketMeta::parse(packet).expect("packet metadata");
        meta.payload(packet)
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
