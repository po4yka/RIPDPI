use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

use ripdpi_desync::DesyncAction;

use crate::platform;

pub use ripdpi_desync::ActivationTransport;
pub type UdpDesyncAction = DesyncAction;

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

pub fn execute_udp_actions(ctx: UdpActionExecContext<'_>, actions: &[UdpDesyncAction]) -> io::Result<()> {
    for action in actions {
        execute_udp_action(ctx, action)?;
    }
    Ok(())
}

fn execute_udp_action(ctx: UdpActionExecContext<'_>, action: &UdpDesyncAction) -> io::Result<()> {
    match action {
        DesyncAction::Write(bytes) => execute_udp_write_action(ctx, bytes),
        DesyncAction::WriteIpFragmentedUdp { bytes, split_offset, disorder, ipv6_ext } => {
            execute_udp_fragmented_write_action(ctx, bytes, *split_offset, *disorder, *ipv6_ext)
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
        | DesyncAction::WriteSeqOverlap { .. } => Ok(()),
    }
}

fn execute_udp_write_action(ctx: UdpActionExecContext<'_>, bytes: &[u8]) -> io::Result<()> {
    if ctx.socks_udp_frame {
        ctx.upstream.send(&frame_socks5_udp_datagram(ctx.target, bytes))?;
    } else {
        ctx.upstream.send(bytes)?;
    }
    Ok(())
}

fn execute_udp_fragmented_write_action(
    ctx: UdpActionExecContext<'_>,
    bytes: &[u8],
    split_offset: usize,
    disorder: bool,
    ipv6_ext: crate::ip_fragmentation::Ipv6ExtHeaders,
) -> io::Result<()> {
    // Raw IP fragmentation targets the final destination directly; through a
    // SOCKS5 UDP relay that path is meaningless, so fall back to a single
    // RFC 1928-framed datagram that the relay forwards to the target.
    if ctx.socks_udp_frame {
        return execute_udp_write_action(ctx, bytes);
    }
    match platform::raw_packet::send_ip_fragmented_udp(
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
        Ok(()) => Ok(()),
        Err(err) if should_fallback_ipfrag_udp_error_kind(err.kind()) => {
            ctx.upstream.send(bytes)?;
            Ok(())
        }
        Err(err) => Err(err),
    }
}

fn should_fallback_ipfrag_udp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput)
}

fn execute_udp_ttl_action(ctx: UdpActionExecContext<'_>, ttl: u8) -> io::Result<()> {
    let socket = socket2::SockRef::from(ctx.upstream);
    match ctx.target {
        SocketAddr::V4(_) => socket.set_ttl_v4(ttl as u32),
        SocketAddr::V6(_) => socket.set_unicast_hops_v6(ttl as u32),
    }
}

fn execute_udp_delay_action(ms: u16) -> io::Result<()> {
    thread::sleep(Duration::from_millis(u64::from(ms)));
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;

    use socket2::SockRef;

    use super::*;

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

        execute_udp_write_action(ctx, b"ping").expect("framed write");

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
}
