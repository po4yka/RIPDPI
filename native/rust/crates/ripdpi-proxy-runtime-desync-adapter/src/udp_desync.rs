use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

use ripdpi_desync::DesyncAction;

use crate::platform;

pub use ripdpi_desync::ActivationTransport;
pub type UdpDesyncAction = DesyncAction;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpExecutionOutcome {
    pub attempted_actions: usize,
    pub completed_actions: usize,
    pub real_writes_committed: usize,
    pub payload_bytes_committed: usize,
    pub technique_actions_completed: usize,
    pub ipv6_extension_profile: Option<ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile>,
    pub fallback_reason: Option<UdpExecutionFallbackReason>,
}

#[derive(Debug)]
pub struct UdpExecutionError {
    source: io::Error,
    pub outcome: UdpExecutionOutcome,
}

impl UdpExecutionError {
    pub fn into_io_error(self) -> io::Error {
        self.source
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpExecutionFallbackReason {
    RelayPathSkippedPacketMutation,
    IpFragmentationFallback,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct UdpActionOutcome {
    real_writes_committed: usize,
    payload_bytes_committed: usize,
    technique_action_completed: bool,
    ipv6_extension_profile: Option<ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile>,
    fallback_reason: Option<UdpExecutionFallbackReason>,
}

#[derive(Clone)]
pub struct UdpDesyncPlanner {
    config: ripdpi_config::RuntimeConfig,
}

pub fn udp_desync_planner(config: &ripdpi_config::RuntimeConfig) -> UdpDesyncPlanner {
    UdpDesyncPlanner { config: config.clone() }
}

#[derive(Clone, Copy)]
pub struct UdpActionExecContext<'a> {
    pub upstream: &'a UdpSocket,
    pub target: SocketAddr,
    pub default_ttl: u8,
    pub protect_path: Option<&'a str>,
    pub ip_id_mode: Option<ripdpi_config::IpIdMode>,
    /// When `true`, the upstream socket is connected to a SOCKS5 UDP relay
    /// endpoint (not the final target directly). Every datagram written to it
    /// must therefore be wrapped in an RFC 1928 UDP request header addressing
    /// [`UdpActionExecContext::target`]; raw IP-fragmented egress is bypassed
    /// because the relay reassembles and re-emits to the target itself.
    pub socks_udp_frame: bool,
}

/// Prepend the RFC 1928 UDP request header (`RSV=0, FRAG=0, ATYP, DST.ADDR,
/// DST.PORT`) addressing `target` to `payload`, producing the datagram body a
/// SOCKS5 UDP relay expects.
fn frame_socks5_udp_datagram(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut packet = Vec::with_capacity(payload.len() + 22);
    packet.extend_from_slice(&[0, 0, 0]);
    match target {
        SocketAddr::V4(addr) => {
            packet.push(0x01);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            packet.push(0x04);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    packet
}

pub struct UdpDesyncPlanContext<'a> {
    pub planner: &'a UdpDesyncPlanner,
    pub runtime_context: Option<&'a ripdpi_proxy_config::ProxyRuntimeContext>,
    pub telemetry: Option<&'a dyn ripdpi_runtime_api::RuntimeTelemetrySink>,
    pub adaptive_hints: &'a dyn ripdpi_runtime_decision_ports::adaptive_ports::AdaptiveHintPort,
}

pub struct UdpDesyncPlanRequest<'a> {
    pub group_index: usize,
    pub payload: &'a [u8],
    pub progress: ripdpi_session::OutboundProgress,
    pub host: Option<&'a str>,
    pub target: SocketAddr,
    pub default_ttl: u8,
}

pub fn plan_udp_actions(
    group: &ripdpi_config::DesyncGroup,
    payload: &[u8],
    default_ttl: u8,
    activation: ripdpi_desync::ActivationContext,
) -> Vec<UdpDesyncAction> {
    ripdpi_desync::plan_udp(group, payload, default_ttl, activation)
}

pub fn plan_udp_actions_for_runtime(
    context: UdpDesyncPlanContext<'_>,
    request: UdpDesyncPlanRequest<'_>,
) -> io::Result<Vec<UdpDesyncAction>> {
    let config = &context.planner.config;
    let group = crate::model::config::selected_desync_group(config, request.group_index)
        .ok_or_else(|| io::Error::other("missing udp route group"))?;
    let adaptive_hints = resolve_udp_hints_for_runtime(&context, &request, group)?;
    let morph_policy = crate::model::proxy_config::morph_policy(context.runtime_context);
    crate::model::proxy_config::emit_morph_hint_applied(
        context.telemetry,
        morph_policy,
        request.target,
        crate::model::proxy_config::udp_morph_hint_family(morph_policy, adaptive_hints),
    );
    let activation = crate::desync_platform::activation_context_from_progress(
        request.progress,
        ActivationTransport::Udp,
        Some(request.payload),
        None,
        None,
        None,
        adaptive_hints,
    );
    Ok(plan_udp_actions(group, request.payload, request.default_ttl, activation))
}

fn resolve_udp_hints_for_runtime(
    context: &UdpDesyncPlanContext<'_>,
    request: &UdpDesyncPlanRequest<'_>,
    group: &ripdpi_config::DesyncGroup,
) -> io::Result<ripdpi_desync::AdaptivePlannerHints> {
    let config = &context.planner.config;
    if config.adaptive.strategy_evolution {
        return context.adaptive_hints.resolve_udp_hints_with_evolver(
            config,
            context.runtime_context,
            request.group_index,
            request.target,
            request.host,
            group,
            request.payload,
        );
    }

    let scope_key = ripdpi_runtime_services::decision_helpers::network_scope_key(config);
    let hints = context.adaptive_hints.resolve_udp_hints(
        scope_key,
        request.group_index,
        request.target,
        request.host,
        group,
        request.payload,
    )?;
    let capability = ripdpi_runtime_services::decision_helpers::direct_path_capability_for_route(
        context.runtime_context,
        request.host,
        request.target,
    );
    let merged = ripdpi_runtime_services::decision_helpers::merge_udp_hints_with_capability(hints, capability);
    if hints.udp_burst_profile != merged.udp_burst_profile || hints.quic_fake_profile != merged.quic_fake_profile {
        crate::model::proxy_config::emit_morph_rollback(
            context.telemetry,
            crate::model::proxy_config::morph_policy(context.runtime_context),
            request.target,
            "direct_path_capability_downgrade",
        );
    }
    Ok(merged)
}

pub fn execute_udp_actions(
    ctx: UdpActionExecContext<'_>,
    actions: &[UdpDesyncAction],
    logical_payload: &[u8],
) -> Result<UdpExecutionOutcome, UdpExecutionError> {
    let mut outcome = UdpExecutionOutcome {
        attempted_actions: actions.len(),
        completed_actions: 0,
        real_writes_committed: 0,
        payload_bytes_committed: 0,
        technique_actions_completed: 0,
        ipv6_extension_profile: None,
        fallback_reason: None,
    };
    for action in actions {
        let action_outcome =
            execute_udp_action(ctx, action, logical_payload).map_err(|source| UdpExecutionError { source, outcome })?;
        outcome.completed_actions = outcome.completed_actions.saturating_add(1);
        outcome.real_writes_committed =
            outcome.real_writes_committed.saturating_add(action_outcome.real_writes_committed);
        outcome.payload_bytes_committed =
            outcome.payload_bytes_committed.saturating_add(action_outcome.payload_bytes_committed);
        outcome.technique_actions_completed =
            outcome.technique_actions_completed.saturating_add(usize::from(action_outcome.technique_action_completed));
        if action_outcome.ipv6_extension_profile.is_some() {
            outcome.ipv6_extension_profile = action_outcome.ipv6_extension_profile;
        }
        if outcome.fallback_reason.is_none() {
            outcome.fallback_reason = action_outcome.fallback_reason;
        }
    }
    Ok(outcome)
}

fn execute_udp_action(
    ctx: UdpActionExecContext<'_>,
    action: &UdpDesyncAction,
    logical_payload: &[u8],
) -> io::Result<UdpActionOutcome> {
    match action {
        DesyncAction::Write(bytes) => execute_udp_write_action(ctx, bytes, bytes == logical_payload),
        DesyncAction::WriteIpFragmentedUdp { bytes, split_offset, disorder, ipv6_ext } => {
            execute_udp_fragmented_write_action(
                ctx,
                bytes,
                *split_offset,
                *disorder,
                *ipv6_ext,
                bytes == logical_payload,
            )
        }
        DesyncAction::SetTtl(ttl) => execute_udp_ttl_action(ctx, *ttl),
        DesyncAction::Delay(ms) => execute_udp_delay_action(*ms),
        DesyncAction::RestoreDefaultTtl
        | DesyncAction::WriteIpFragmentedTcp { .. }
        | DesyncAction::WriteUrgent { .. }
        | DesyncAction::SetMd5Sig { .. }
        | DesyncAction::AttachDropSack
        | DesyncAction::DetachDropSack
        | DesyncAction::AwaitWritable
        | DesyncAction::SetWindowClamp(_)
        | DesyncAction::RestoreWindowClamp
        | DesyncAction::SetWsize { .. }
        | DesyncAction::RestoreWsize
        | DesyncAction::SendFakeRst
        | DesyncAction::WriteSeqOverlap { .. } => Ok(UdpActionOutcome {
            real_writes_committed: 0,
            payload_bytes_committed: 0,
            technique_action_completed: false,
            ipv6_extension_profile: None,
            fallback_reason: None,
        }),
    }
}

fn execute_udp_write_action(
    ctx: UdpActionExecContext<'_>,
    bytes: &[u8],
    logical_payload: bool,
) -> io::Result<UdpActionOutcome> {
    if ctx.socks_udp_frame {
        ctx.upstream.send(&frame_socks5_udp_datagram(ctx.target, bytes))?;
    } else {
        ctx.upstream.send(bytes)?;
    }
    Ok(UdpActionOutcome {
        real_writes_committed: usize::from(logical_payload),
        payload_bytes_committed: if logical_payload { bytes.len() } else { 0 },
        technique_action_completed: !logical_payload,
        ipv6_extension_profile: None,
        fallback_reason: None,
    })
}

fn execute_udp_fragmented_write_action(
    ctx: UdpActionExecContext<'_>,
    bytes: &[u8],
    split_offset: usize,
    disorder: bool,
    ipv6_ext: crate::ip_fragmentation::Ipv6ExtHeaders,
    logical_payload: bool,
) -> io::Result<UdpActionOutcome> {
    // Raw IP fragmentation targets the final destination directly; through a
    // SOCKS5 UDP relay that path is meaningless, so fall back to a single
    // RFC 1928-framed datagram that the relay forwards to the target.
    if ctx.socks_udp_frame {
        let mut outcome = execute_udp_write_action(ctx, bytes, logical_payload)?;
        outcome.fallback_reason = Some(UdpExecutionFallbackReason::RelayPathSkippedPacketMutation);
        return Ok(outcome);
    }
    let fallback_reason = match platform::raw_packet::send_ip_fragmented_udp(
        ctx.upstream,
        ctx.target,
        bytes,
        split_offset,
        ctx.default_ttl,
        ctx.protect_path,
        disorder,
        ipv6_ext,
        ctx.ip_id_mode,
    ) {
        Ok(()) => None,
        Err(err) if should_fallback_ipfrag_udp_error_kind(err.kind()) => {
            ctx.upstream.send(bytes)?;
            Some(UdpExecutionFallbackReason::IpFragmentationFallback)
        }
        Err(err) => return Err(err),
    };
    Ok(UdpActionOutcome {
        real_writes_committed: usize::from(logical_payload),
        payload_bytes_committed: if logical_payload { bytes.len() } else { 0 },
        technique_action_completed: true,
        ipv6_extension_profile: applied_ipv6_extension_profile(ctx.target, ipv6_ext, fallback_reason),
        fallback_reason,
    })
}

fn execute_udp_ttl_action(ctx: UdpActionExecContext<'_>, ttl: u8) -> io::Result<UdpActionOutcome> {
    // A low/fake TTL is a desync trick aimed at the path to the *final target*.
    // Through a SOCKS5 UDP relay the socket is connected to the trusted relay
    // endpoint, so a low TTL would expire the datagram at/before the relay
    // (dropping it) while achieving no DPI desync. Skip it on the relay path --
    // mirrors execute_udp_fragmented_write_action's relay fallback.
    if ctx.socks_udp_frame {
        return Ok(UdpActionOutcome {
            real_writes_committed: 0,
            payload_bytes_committed: 0,
            technique_action_completed: false,
            ipv6_extension_profile: None,
            fallback_reason: Some(UdpExecutionFallbackReason::RelayPathSkippedPacketMutation),
        });
    }
    let socket = socket2::SockRef::from(ctx.upstream);
    match ctx.target {
        SocketAddr::V4(_) => socket.set_ttl_v4(ttl as u32),
        SocketAddr::V6(_) => socket.set_unicast_hops_v6(ttl as u32),
    }?;
    Ok(UdpActionOutcome {
        real_writes_committed: 0,
        payload_bytes_committed: 0,
        technique_action_completed: false,
        ipv6_extension_profile: None,
        fallback_reason: None,
    })
}

fn execute_udp_delay_action(ms: u16) -> io::Result<UdpActionOutcome> {
    thread::sleep(Duration::from_millis(u64::from(ms)));
    Ok(UdpActionOutcome {
        real_writes_committed: 0,
        payload_bytes_committed: 0,
        technique_action_completed: false,
        ipv6_extension_profile: None,
        fallback_reason: None,
    })
}

fn ipv6_extension_profile(
    headers: crate::ip_fragmentation::Ipv6ExtHeaders,
) -> Option<ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile> {
    use ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile as Profile;
    if headers.routing || headers.second_frag_next_override.is_some() {
        return Some(Profile::Unknown);
    }
    match (headers.hop_by_hop, headers.dest_opt, headers.dest_opt_fragmentable) {
        (false, false, false) => None,
        (true, false, false) => Some(Profile::HopByHop),
        (true, false, true) => Some(Profile::HopByHop2),
        (false, true, false) => Some(Profile::DestinationOptions),
        (true, true, false) => Some(Profile::HopByHopDestinationOptions),
        _ => Some(Profile::Unknown),
    }
}

fn applied_ipv6_extension_profile(
    target: SocketAddr,
    headers: crate::ip_fragmentation::Ipv6ExtHeaders,
    fallback_reason: Option<UdpExecutionFallbackReason>,
) -> Option<ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile> {
    if target.is_ipv4() || fallback_reason.is_some() {
        return None;
    }
    ipv6_extension_profile(headers)
}

fn should_fallback_ipfrag_udp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput)
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;

    use socket2::SockRef;

    use super::*;

    #[test]
    fn ipv6_extension_headers_project_to_exact_bounded_profile() {
        use ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile as Profile;

        assert_eq!(ipv6_extension_profile(crate::ip_fragmentation::Ipv6ExtHeaders::default()), None);
        assert_eq!(
            ipv6_extension_profile(crate::ip_fragmentation::Ipv6ExtHeaders {
                hop_by_hop: true,
                dest_opt: true,
                ..Default::default()
            }),
            Some(Profile::HopByHopDestinationOptions),
        );
        assert_eq!(
            ipv6_extension_profile(crate::ip_fragmentation::Ipv6ExtHeaders { routing: true, ..Default::default() }),
            Some(Profile::Unknown),
        );
    }

    #[test]
    fn ipv6_extension_profile_is_only_evidence_for_successful_ipv6_fragmentation() {
        use ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile as Profile;

        let headers = crate::ip_fragmentation::Ipv6ExtHeaders { hop_by_hop: true, ..Default::default() };
        assert_eq!(
            applied_ipv6_extension_profile("[2001:db8::1]:443".parse().unwrap(), headers, None),
            Some(Profile::HopByHop),
        );
        assert_eq!(applied_ipv6_extension_profile("192.0.2.1:443".parse().unwrap(), headers, None), None);
        assert_eq!(
            applied_ipv6_extension_profile(
                "[2001:db8::1]:443".parse().unwrap(),
                headers,
                Some(UdpExecutionFallbackReason::IpFragmentationFallback),
            ),
            None,
        );
    }

    #[test]
    fn udp_ttl_action_sets_ipv4_ttl() {
        let socket = UdpSocket::bind("127.0.0.1:0").expect("bind IPv4 UDP socket");
        let ctx = UdpActionExecContext {
            upstream: &socket,
            target: "127.0.0.1:443".parse().expect("IPv4 target"),
            default_ttl: 64,
            protect_path: None,
            ip_id_mode: None,
            socks_udp_frame: false,
        };

        execute_udp_ttl_action(ctx, 17).expect("set IPv4 TTL");

        assert_eq!(SockRef::from(&socket).ttl_v4().expect("read IPv4 TTL"), 17);
    }

    #[test]
    fn socks5_udp_frame_prepends_rfc1928_ipv4_header() {
        let target: SocketAddr = "203.0.113.7:443".parse().expect("IPv4 target");
        let framed = frame_socks5_udp_datagram(target, b"ping");
        assert_eq!(framed, vec![0, 0, 0, 0x01, 203, 0, 113, 7, 0x01, 0xbb, b'p', b'i', b'n', b'g']);
    }

    #[test]
    fn socks5_udp_frame_prepends_rfc1928_ipv6_header() {
        let target: SocketAddr = "[2001:db8::1]:443".parse().expect("IPv6 target");
        let framed = frame_socks5_udp_datagram(target, b"x");
        assert_eq!(framed[..4], [0, 0, 0, 0x04]);
        assert_eq!(framed[20..22], [0x01, 0xbb]);
        assert_eq!(framed[22], b'x');
    }

    #[test]
    fn write_action_through_socks_frame_wraps_payload() {
        let relay = UdpSocket::bind("127.0.0.1:0").expect("bind relay");
        let relay_addr = relay.local_addr().expect("relay addr");
        let sender = UdpSocket::bind("127.0.0.1:0").expect("bind sender");
        sender.connect(relay_addr).expect("connect sender");
        let ctx = UdpActionExecContext {
            upstream: &sender,
            target: "203.0.113.7:443".parse().expect("IPv4 target"),
            default_ttl: 64,
            protect_path: None,
            ip_id_mode: None,
            socks_udp_frame: true,
        };

        execute_udp_write_action(ctx, b"ping", true).expect("framed write");

        let mut buf = [0u8; 64];
        let n = relay.recv(&mut buf).expect("recv framed datagram");
        assert_eq!(&buf[..n], &[0, 0, 0, 0x01, 203, 0, 113, 7, 0x01, 0xbb, b'p', b'i', b'n', b'g']);
    }

    #[test]
    fn udp_ttl_action_sets_ipv6_hop_limit() {
        let Ok(socket) = UdpSocket::bind("[::1]:0") else {
            return;
        };
        let ctx = UdpActionExecContext {
            upstream: &socket,
            target: "[::1]:443".parse().expect("IPv6 target"),
            default_ttl: 64,
            protect_path: None,
            ip_id_mode: None,
            socks_udp_frame: false,
        };

        execute_udp_ttl_action(ctx, 17).expect("set IPv6 hop limit");

        assert_eq!(SockRef::from(&socket).unicast_hops_v6().expect("read IPv6 hop limit"), 17);
    }

    #[test]
    fn udp_ttl_action_skipped_when_routing_through_socks5_relay() {
        let socket = UdpSocket::bind("127.0.0.1:0").expect("bind IPv4 UDP socket");
        // Establish a known baseline, then attempt a low (would-be desync) TTL on
        // the SOCKS5-relay path: it must be left untouched, otherwise the datagram
        // would expire at/before the trusted relay instead of reaching it.
        SockRef::from(&socket).set_ttl_v4(55).expect("set baseline TTL");
        let ctx = UdpActionExecContext {
            upstream: &socket,
            target: "127.0.0.1:443".parse().expect("IPv4 target"),
            default_ttl: 64,
            protect_path: None,
            ip_id_mode: None,
            socks_udp_frame: true,
        };

        execute_udp_ttl_action(ctx, 1).expect("ttl action is a no-op on the relay path");

        assert_eq!(
            SockRef::from(&socket).ttl_v4().expect("read IPv4 TTL"),
            55,
            "TTL must be unchanged when routing through a SOCKS5 UDP relay",
        );
    }
}
