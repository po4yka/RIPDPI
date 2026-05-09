use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::platform::udp as udp_platform;

use super::encode_socks5_udp_packet;
use super::feedback::note_udp_first_response_success;
use super::flow::UdpFlowActivationState;
use super::migration::maybe_rebind_udp_source_port;
use super::observations::{observe_datagram_outbound, observe_upstream_response};
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
                observe_upstream_response(entry, &upstream_buffer[..n]);
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
    entry.last_used = now;
    entry.payload.clear();
    entry.payload.extend_from_slice(payload);
    entry.awaiting_response = true;
    let progress = observe_datagram_outbound(entry, payload);
    let actions = state.plan_udp_flow_actions(
        entry.route.group_index,
        payload,
        progress,
        entry.host.as_deref(),
        entry.current_target,
        entry.packet_settings.default_ttl,
    )?;
    RuntimeState::execute_udp_desync_actions(
        &entry.upstream,
        entry.current_target,
        entry.packet_settings,
        protect_path,
        &actions,
    )
}
