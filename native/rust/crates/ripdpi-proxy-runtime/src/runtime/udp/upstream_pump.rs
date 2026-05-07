use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::{selected_desync_group, udp_default_ttl, udp_ip_id_mode};
use ripdpi_proxy_runtime_adapter::udp_desync::{
    execute_udp_actions, plan_udp_actions, ActivationTransport, UdpActionExecContext, UdpDesyncAction,
};

use super::feedback::note_udp_first_response_success;
use super::flow::UdpFlowActivationState;
use super::migration::maybe_rebind_udp_source_port;
use super::response_encode::encode_udp_response;
use crate::runtime::adaptive::resolve_udp_hints_with_evolver;
use crate::runtime::morph::{emit_morph_hint_applied, udp_morph_hint_family};
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
                entry.session.observe_inbound(&upstream_buffer[..n]);
                note_udp_first_response_success(state, entry)?;
                maybe_rebind_udp_source_port(state, entry, &upstream_buffer[..n], protect_path)?;
                let packet = encode_udp_response(entry.current_target, &upstream_buffer[..n]);
                client_relay.send_to(&packet, client_addr)?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) if err.raw_os_error() == Some(libc::ECONNREFUSED) => {}
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
        default_ttl: udp_default_ttl(&state.config),
        protect_path,
        ip_id_mode: udp_ip_id_mode(&state.config, entry.route.group_index),
    };
    execute_udp_actions(exec_ctx, &actions)
}

fn plan_udp_flow_actions(
    state: &RuntimeState,
    entry: &mut UdpFlowActivationState,
    payload: &[u8],
    now: Instant,
) -> io::Result<Vec<UdpDesyncAction>> {
    let group = selected_desync_group(&state.config, entry.route.group_index)
        .ok_or_else(|| io::Error::other("missing udp route group"))?;
    let adaptive_hints = resolve_udp_hints_with_evolver(
        state,
        entry.current_target,
        entry.route.group_index,
        group,
        entry.host.as_deref(),
        payload,
    )?;
    emit_morph_hint_applied(state, entry.current_target, udp_morph_hint_family(state, adaptive_hints));
    entry.last_used = now;
    entry.payload.clear();
    entry.payload.extend_from_slice(payload);
    entry.awaiting_response = true;
    let progress = entry.session.observe_datagram_outbound(payload);
    let activation = crate::runtime::desync::activation_context_from_progress(
        progress,
        ActivationTransport::Udp,
        Some(payload),
        None,
        None,
        None,
        adaptive_hints,
    );
    Ok(plan_udp_actions(group, payload, udp_default_ttl(&state.config), activation))
}
