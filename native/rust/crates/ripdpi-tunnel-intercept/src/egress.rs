use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::path::Path;
use std::time::{Duration, Instant};

use ripdpi_packets::{
    QuicInitialLayout, http_marker_info, parse_quic_initial, parse_quic_initial_layout, parse_tls,
    second_level_domain_span, tls_marker_info,
};
use ripdpi_strategy_config::{
    LoadedStrategy, LoadedStrategyConfig, OnFail, ProtocolName, StepType, StrategyMatch, StrategyStep,
};
use ripdpi_strategy_ipv6::{Ipv6ExtType, apply_ipv6_ext_header};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    HttpDissect, L7Protocol, MarkerName, QuicDissect, RuntimeCapability, StrategyContext, StrategyVerdict, TlsDissect,
};
use tracing::{debug, warn};

mod packet;
#[cfg(test)]
mod upstream_vectors;

use self::packet::{
    PacketMeta, Transport, flow_id, low_ttl_tcp_copy, packet_destination, packet_with_payload, set_packet_hop_limit,
    transport_endpoint,
};

#[cfg(test)]
use self::packet::{IPV4_MIN_HEADER_LEN, IPV6_HEADER_LEN, TCP_PROTO, UDP_PROTO, recompute_ipv4_header_checksum};

pub trait TunPacketInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()>;
}

pub trait TunEgressPacketHandler: Send {
    fn handle_packet(&mut self, packet: &[u8]) -> bool;

    /// Releases state when the tunnel closes an exact transport flow.
    fn close_flow(&mut self, _protocol: u8, _src: SocketAddr, _dst: SocketAddr) {}

    /// Releases every UDP flow owned by a closed source association.
    fn close_udp_source(&mut self, _src: SocketAddr) {}

    /// Marks a flow that reached a live UDP association.
    fn mark_udp_associated(&mut self, _src: SocketAddr, _dst: SocketAddr) {}

    /// Expires idle UDP flows without a live owning association.
    fn expire_idle_udp_flows(&mut self, _timeout: Duration, _has_association: &dyn Fn(SocketAddr) -> bool) {}
}

pub struct RawTunPacketInjector {
    protect_path: Option<String>,
}

impl RawTunPacketInjector {
    pub fn new(protect_path: Option<String>) -> Self {
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

pub struct TunEgressInterceptor<I> {
    rules: Vec<EgressRule>,
    injector: I,
}

impl<I: TunPacketInjector> TunEgressInterceptor<I> {
    /// Builds an interceptor from inline strategy YAML, resolving `lua` step
    /// `script_paths` relative to the current directory.
    pub fn new(strategy_yaml: Option<&str>, injector: I) -> Self {
        Self::new_with_base_dir(strategy_yaml, Path::new("."), injector)
    }

    /// Builds an interceptor, jailing `lua` step `script_paths` to `base_dir`.
    ///
    /// `base_dir` is the trust anchor the Lua engine is confined to: a
    /// `script_paths` entry that canonicalizes outside it (absolute path or
    /// `../` escape) is rejected. File-backed callers pass the directory of
    /// the strategy file; inline-YAML callers pass `.`.
    pub fn new_with_base_dir(strategy_yaml: Option<&str>, base_dir: &Path, injector: I) -> Self {
        Self::new_with_lua_owner(strategy_yaml, base_dir, false, injector)
    }

    /// Skips Lua steps when the proxy owns their non-root socket execution.
    pub fn new_with_lua_owner(
        strategy_yaml: Option<&str>,
        base_dir: &Path,
        lua_socket_owned: bool,
        injector: I,
    ) -> Self {
        let rules = strategy_yaml.map(|yaml| parse_rules(yaml, base_dir, lua_socket_owned)).unwrap_or_default();
        Self { rules, injector }
    }

    pub fn handle_packet(&mut self, packet: &[u8]) -> bool {
        if self.rules.is_empty() {
            return false;
        }
        let Some(meta) = PacketMeta::parse(packet) else {
            return false;
        };
        let udp_protocol = if meta.transport == Transport::Udp {
            ripdpi_protocol_detect::classify_l7(meta.payload(packet), meta.src_port, meta.dst_port, true)
        } else {
            L7Protocol::Unknown
        };

        let host = self
            .rules
            .iter()
            .any(|rule| !rule.matcher.hosts.is_empty())
            .then(|| packet_host(meta.transport, meta.payload(packet)))
            .flatten();
        let mut consumed = false;
        for rule in &mut self.rules {
            if !rule.matcher.matches(
                meta,
                &udp_protocol,
                host.as_ref().map(|(protocol, host)| (*protocol, host.as_str())),
            ) {
                continue;
            }
            if rule.action.apply(packet, meta, &mut self.injector) {
                consumed = true;
                break;
            }
        }
        if meta.is_tcp_terminal(packet) {
            self.close_flow(6, meta.src_addr(), meta.dst_addr());
        }
        consumed
    }

    pub fn close_flow(&mut self, protocol: u8, src: SocketAddr, dst: SocketAddr) {
        let transport = match protocol {
            6 => Transport::Tcp,
            17 => Transport::Udp,
            _ => return,
        };
        let flow_id = FlowId(flow_id(transport, src, dst));
        for rule in &mut self.rules {
            if let EgressRuleAction::Strategy { registry, udp_flows, .. } = &mut rule.action {
                udp_flows.remove(&flow_id);
                if let Err(error) = registry.close_flow(flow_id) {
                    warn!("failed to close TUN Lua flow state: {error:?}");
                }
            }
        }
    }

    pub fn close_udp_source(&mut self, src: SocketAddr) {
        for rule in &mut self.rules {
            if let EgressRuleAction::Strategy { registry, udp_flows, .. } = &mut rule.action {
                udp_flows.retain(|&flow_id, flow| {
                    if flow.src != src || !flow.associated {
                        return true;
                    }
                    if let Err(error) = registry.close_flow(flow_id) {
                        warn!("failed to close TUN Lua UDP state: {error:?}");
                    }
                    false
                });
            }
        }
    }

    pub fn mark_udp_associated(&mut self, src: SocketAddr, dst: SocketAddr) {
        let flow_id = FlowId(flow_id(Transport::Udp, src, dst));
        for rule in &mut self.rules {
            if let EgressRuleAction::Strategy { udp_flows, .. } = &mut rule.action
                && let Some(flow) = udp_flows.get_mut(&flow_id)
            {
                flow.associated = true;
            }
        }
    }

    pub fn expire_idle_udp_flows(&mut self, timeout: Duration, has_association: &dyn Fn(SocketAddr) -> bool) {
        let now = Instant::now();
        for rule in &mut self.rules {
            if let EgressRuleAction::Strategy { registry, udp_flows, .. } = &mut rule.action {
                udp_flows.retain(|&flow_id, flow| {
                    if (flow.associated && has_association(flow.src)) || now.duration_since(flow.last_seen) < timeout {
                        return true;
                    }
                    if let Err(error) = registry.close_flow(flow_id) {
                        warn!("failed to expire TUN Lua UDP state: {error:?}");
                    }
                    false
                });
            }
        }
    }
}

impl<I: TunPacketInjector + Send> TunEgressPacketHandler for TunEgressInterceptor<I> {
    fn handle_packet(&mut self, packet: &[u8]) -> bool {
        TunEgressInterceptor::handle_packet(self, packet)
    }

    fn close_flow(&mut self, protocol: u8, src: SocketAddr, dst: SocketAddr) {
        TunEgressInterceptor::close_flow(self, protocol, src, dst);
    }

    fn close_udp_source(&mut self, src: SocketAddr) {
        TunEgressInterceptor::close_udp_source(self, src);
    }

    fn mark_udp_associated(&mut self, src: SocketAddr, dst: SocketAddr) {
        TunEgressInterceptor::mark_udp_associated(self, src, dst);
    }

    fn expire_idle_udp_flows(&mut self, timeout: Duration, has_association: &dyn Fn(SocketAddr) -> bool) {
        TunEgressInterceptor::expire_idle_udp_flows(self, timeout, has_association);
    }
}

fn parse_rules(strategy_yaml: &str, base_dir: &Path, lua_socket_owned: bool) -> Vec<EgressRule> {
    match ripdpi_strategy_config::parse_yaml_str(strategy_yaml, base_dir) {
        Ok(config) => config
            .strategies
            .iter()
            .flat_map(|strategy| rules_for_strategy(strategy, &config.base_dir, lua_socket_owned))
            .collect(),
        Err(error) => {
            warn!("failed to parse strategy YAML for TUN egress interception: {error}");
            Vec::new()
        }
    }
}

fn rules_for_strategy(strategy: &LoadedStrategy, base_dir: &Path, lua_socket_owned: bool) -> Vec<EgressRule> {
    strategy
        .steps
        .iter()
        .filter(|step| !lua_socket_owned || step.kind != StepType::Lua)
        .filter_map(|step| {
            EgressRuleAction::from_step(step, base_dir, strategy.on_fail)
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
    Strategy { registry: StrategyRegistry, forward_original: bool, udp_flows: HashMap<FlowId, UdpFlowState> },
}

struct UdpFlowState {
    src: SocketAddr,
    last_seen: Instant,
    associated: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EgressAction {
    Fake { ttl: u8 },
    UdpLen { delta: i16 },
    Ipv6Ext { ext_type: Ipv6ExtType },
}

impl EgressAction {
    fn from_step(step: &StrategyStep) -> Option<Self> {
        match &step.kind {
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
    fn from_step(step: &StrategyStep, base_dir: &Path, on_fail: OnFail) -> Option<Self> {
        if step.kind == StepType::Lua {
            return Self::lua_strategy(step, base_dir, on_fail);
        }
        EgressAction::from_step(step).map(Self::Direct)
    }

    fn lua_strategy(step: &StrategyStep, base_dir: &Path, on_fail: OnFail) -> Option<Self> {
        let config = LoadedStrategyConfig {
            version: 1,
            base_dir: base_dir.to_path_buf(),
            strategies: vec![LoadedStrategy {
                id: step.function.clone().unwrap_or_else(|| "lua".to_owned()),
                matcher: StrategyMatch::default(),
                steps: vec![step.clone()],
                on_fail,
            }],
        };
        match StrategyRegistry::from_loaded_config(&config) {
            Ok(registry) => Some(Self::Strategy {
                registry,
                forward_original: step.forward_original.unwrap_or(false),
                udp_flows: HashMap::new(),
            }),
            Err(error) => {
                warn!("failed to materialize Lua TUN egress strategy: {error}");
                None
            }
        }
    }

    fn apply<I: TunPacketInjector>(&mut self, packet: &[u8], meta: PacketMeta, injector: &mut I) -> bool {
        match self {
            Self::Direct(action) => inject_direct_action(*action, packet, injector),
            Self::Strategy { registry, forward_original, udp_flows } => {
                let consumed = execute_registry_action(registry, *forward_original, packet, meta, injector);
                if meta.transport == Transport::Udp {
                    let flow_id = FlowId(meta.flow_id());
                    if registry.has_flow(flow_id) {
                        udp_flows.entry(flow_id).or_insert(UdpFlowState {
                            src: meta.src_addr(),
                            last_seen: Instant::now(),
                            associated: false,
                        });
                        if let Some(flow) = udp_flows.get_mut(&flow_id) {
                            flow.last_seen = Instant::now();
                        }
                    }
                }
                consumed
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
    let mut all_injected = true;
    let mut sequence_delta = 0u32;
    let mut ttl = None;
    let pure_drop = drop_original && plan.actions.is_empty();
    for action in plan.actions {
        match action {
            DesyncAction::RawSend(output) | DesyncAction::Write(output) => {
                if inject_strategy_output(packet, meta, &output, sequence_delta, ttl, injector) {
                    injected = true;
                    sequence_delta = sequence_delta.wrapping_add(output.len() as u32);
                } else {
                    all_injected = false;
                }
                ttl = None;
            }
            DesyncAction::Split { offset, disorder } => {
                if let Some((first, second)) = split_payload(payload, offset) {
                    let (first_sent, second_sent) = if disorder {
                        let first_sent =
                            inject_strategy_output(packet, meta, second, first.len() as u32, ttl, injector);
                        (first_sent, first_sent && inject_strategy_output(packet, meta, first, 0, ttl, injector))
                    } else {
                        let first_sent = inject_strategy_output(packet, meta, first, 0, ttl, injector);
                        (
                            first_sent,
                            first_sent
                                && inject_strategy_output(packet, meta, second, first.len() as u32, ttl, injector),
                        )
                    };
                    injected |= first_sent || second_sent;
                    all_injected &= first_sent && second_sent;
                } else {
                    all_injected = false;
                }
                ttl = None;
            }
            DesyncAction::SetTtl(next_ttl) => ttl = Some(next_ttl),
            DesyncAction::RestoreDefaultTtl => ttl = None,
            DesyncAction::WriteFake { ttl: fake_ttl, .. } => {
                if let Some(output) = low_ttl_tcp_copy(packet, fake_ttl.or(ttl).unwrap_or(5)) {
                    let sent = inject_strategy_output(packet, meta, &output, 0, None, injector);
                    injected |= sent;
                    all_injected &= sent;
                } else {
                    all_injected = false;
                }
                ttl = None;
            }
            DesyncAction::UdpLen { delta } => {
                if let Some(output) = ripdpi_strategy_udp::apply_udplen(packet, delta) {
                    let sent = inject_strategy_output(packet, meta, &output, 0, None, injector);
                    injected |= sent;
                    all_injected &= sent;
                } else {
                    all_injected = false;
                }
                ttl = None;
            }
            DesyncAction::SetWindowClamp(_) | DesyncAction::WriteUrgent { .. } | DesyncAction::SendFakeRst { .. } => {
                debug!("Lua TUN egress action is unsupported on raw TUN path; forwarding original packet");
                all_injected = false;
            }
        }
        if !all_injected {
            break;
        }
    }
    pure_drop || (injected && all_injected && (drop_original || !forward_original))
}

fn dissect_packet(meta: PacketMeta, payload: &[u8]) -> Dissect {
    let mut dissect = Dissect {
        proto: classify_l7(meta, payload),
        src_port: meta.src_port,
        dst_port: meta.dst_port,
        is_ipv6: meta.is_ipv6,
        tcp_mss: None,
        markers: HashMap::new(),
    };
    populate_markers(&mut dissect, payload);
    dissect
}

fn classify_l7(meta: PacketMeta, payload: &[u8]) -> L7Protocol {
    match meta.transport {
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
        Transport::Udp => parse_quic_initial(payload).map_or_else(
            || ripdpi_protocol_detect::classify_l7(payload, meta.src_port, meta.dst_port, true),
            |quic| L7Protocol::Quic(QuicDissect { version: Some(quic.version) }),
        ),
    }
}

fn packet_host(transport: Transport, payload: &[u8]) -> Option<(ProtocolName, String)> {
    match transport {
        Transport::Tcp => parse_tls(payload)
            .map(|host| (ProtocolName::Tls, String::from_utf8_lossy(host).trim_end_matches('.').to_ascii_lowercase()))
            .or_else(|| {
                http_marker_info(payload).and_then(|markers| payload.get(markers.host_start..markers.host_end)).map(
                    |host| {
                        (ProtocolName::Http, String::from_utf8_lossy(host).trim_end_matches('.').to_ascii_lowercase())
                    },
                )
            }),
        Transport::Udp => parse_quic_initial_layout(payload).and_then(|layout| {
            layout.info.client_hello.get(layout.info.tls_info.host_start..layout.info.tls_info.host_end).map(|host| {
                (ProtocolName::Quic, String::from_utf8_lossy(host).trim_end_matches('.').to_ascii_lowercase())
            })
        }),
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
        if let Some(ttl) = ttl
            && set_packet_hop_limit(&mut packet, ttl).is_none()
        {
            return false;
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
    hosts: Vec<String>,
}

impl PacketMatcher {
    fn from_strategy(strategy: &LoadedStrategy) -> Self {
        Self {
            proto: strategy.matcher.proto.clone(),
            ports: strategy.matcher.port.clone(),
            hosts: strategy.matcher.hosts.iter().map(|host| host.trim_end_matches('.').to_ascii_lowercase()).collect(),
        }
    }

    fn matches(&self, meta: PacketMeta, udp_protocol: &L7Protocol, host: Option<(ProtocolName, &str)>) -> bool {
        self.matches_port(meta) && self.matches_proto(meta, udp_protocol) && self.matches_host(host)
    }

    fn matches_host(&self, host: Option<(ProtocolName, &str)>) -> bool {
        if self.hosts.is_empty() {
            return true;
        }
        let Some((protocol, host)) = host else { return false };
        if !self.proto.is_empty() && !self.proto.contains(&ProtocolName::Any) && !self.proto.contains(&protocol) {
            return false;
        }
        self.hosts
            .iter()
            .any(|rule| host == rule || host.strip_suffix(rule).is_some_and(|prefix| prefix.ends_with('.')))
    }

    fn matches_port(&self, meta: PacketMeta) -> bool {
        self.ports.is_empty() || self.ports.iter().any(|port| *port == meta.src_port || *port == meta.dst_port)
    }

    fn matches_proto(&self, meta: PacketMeta, udp_protocol: &L7Protocol) -> bool {
        self.proto.is_empty()
            || self.proto.iter().any(|proto| {
                if meta.transport == Transport::Udp {
                    return matches!(
                        (proto, udp_protocol),
                        (ProtocolName::Any, _)
                            | (ProtocolName::Quic, L7Protocol::Quic(_))
                            | (ProtocolName::Dtls, L7Protocol::Dtls(_))
                            | (ProtocolName::Stun, L7Protocol::Stun(_))
                            | (ProtocolName::Dht, L7Protocol::Dht(_))
                            | (ProtocolName::Wireguard, L7Protocol::WireGuard(_))
                    );
                }
                matches!(
                    (proto, meta.transport),
                    (ProtocolName::Any, _)
                        | (ProtocolName::Tls | ProtocolName::Http | ProtocolName::Mtproto, Transport::Tcp)
                )
            })
    }
}

#[cfg(test)]
mod tests;
