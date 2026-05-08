use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::session::observe_inbound_payload;
use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;
use ripdpi_proxy_runtime_adapter::udp_desync::{
    execute_udp_actions, plan_udp_actions_for_runtime, UdpActionExecContext, UdpDesyncAction, UdpDesyncPlanContext,
    UdpDesyncPlanRequest,
};

use super::encode_socks5_udp_packet;
use super::feedback::note_udp_first_response_success;
use super::flow::UdpFlowActivationState;
use super::migration::maybe_rebind_udp_source_port;
use crate::runtime::state::RuntimeState;

pub(super) fn pump_udp_upstream_responses(
    state: &RuntimeState,
    client_relay: &UdpSocket,
    upstream_buffer: &mut [u8],
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    protect_path: Option<&str>,
) -> io::Result<bool> {
    let mut made_progress = false;
    for (&(client_addr, _logical_target), entry) in flow_state {
        match entry.upstream.recv(upstream_buffer) {
            Ok(n) => {
                made_progress = true;
                let now = Instant::now();
                entry.last_used = now;
                observe_inbound_payload(&mut entry.session, &upstream_buffer[..n]);
                note_udp_first_response_success(state, entry)?;
                maybe_rebind_udp_source_port(state, entry, &upstream_buffer[..n], protect_path)?;
                let packet = encode_socks5_udp_packet(entry.current_target, &upstream_buffer[..n]);
                client_relay.send_to(&packet, client_addr)?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) if udp_platform::is_connection_refused(&err) => {}
            Err(err) => return Err(err),
        }
    }
    Ok(made_progress)
}

pub(super) fn send_udp_flow_payload(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
    protect_path: Option<&str>,
) -> io::Result<()> {
    let actions = plan_udp_flow_actions(state, entry, payload, now)?;
    let exec_ctx = UdpActionExecContext {
        upstream: &entry.upstream,
        target: entry.current_target,
        default_ttl: entry.packet_settings.default_ttl,
        protect_path,
        ip_id_mode: entry.packet_settings.ip_id_mode,
    };
    execute_udp_actions(exec_ctx, &actions)
}

fn plan_udp_flow_actions(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
) -> io::Result<Vec<UdpDesyncAction>> {
    entry.last_used = now;
    entry.payload.clear();
    entry.payload.extend_from_slice(payload);
    entry.awaiting_response = true;
    let progress = entry.session.observe_datagram_outbound(payload);
    plan_udp_actions_for_runtime(
        UdpDesyncPlanContext {
            planner: &state.udp_desync_planner,
            runtime_context: state.runtime_context.as_ref(),
            telemetry: state.telemetry.as_deref(),
            adaptive_hints: state.adaptive_hints(),
        },
        UdpDesyncPlanRequest {
            group_index: entry.route.group_index,
            payload,
            progress,
            host: entry.host.as_deref(),
            target: entry.current_target,
            default_ttl: entry.packet_settings.default_ttl,
        },
    )
}
