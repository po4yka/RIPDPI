mod client_receive;
mod feedback;
mod flow;
mod flow_selection;
mod migration;
mod session;
mod settings;
mod sockets;
mod upstream_pump;
mod upstream_socks;

#[cfg(all(test, not(feature = "loom")))]
mod tests;

use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use crate::sync::{Arc, AtomicBool, Ordering};
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

use self::client_receive::receive_and_forward_udp_client_packet;
use self::flow::{UdpFlowActivationState, UdpFlowExpirySchedule, UdpFlowKey, expire_udp_flows};
pub(in crate::runtime) use self::settings::{
    RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy, UdpFlowGroupPolicy,
    runtime_udp_packet_settings,
};
pub(crate) use self::sockets::build_udp_relay_sockets;
use self::upstream_pump::{UdpUpstreamPollScratch, pump_udp_upstream_responses};
use super::adaptive::emit_due_direct_path_learning_timeouts;
use super::state::RuntimeState;

#[allow(dead_code)]
pub(crate) fn encode_socks5_udp_packet(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    RuntimeState::encode_socks5_udp_packet(target, payload)
}

pub(crate) fn encode_socks5_udp_packet_with_resolved_host_into(
    out: &mut Vec<u8>,
    target: SocketAddr,
    host: &str,
    payload: &[u8],
) {
    RuntimeState::encode_socks5_udp_packet_with_resolved_host_into(out, target, host, payload);
}

#[cfg(test)]
pub(crate) fn parse_socks5_udp_packet<'a>(packet: &'a [u8], state: &RuntimeState) -> Option<(SocketAddr, &'a [u8])> {
    state.parse_socks5_udp_packet(packet, |host, socket_type| state.resolve_proxy_name(host, socket_type))
}

fn parse_socks5_udp_packet_with_host<'a>(
    packet: &'a [u8],
    state: &RuntimeState,
) -> Option<ripdpi_proxy_runtime_adapter::model::session::ParsedSocks5UdpPacket<'a>> {
    state.parse_socks5_udp_packet_with_host(packet, |host, socket_type| state.resolve_proxy_name(host, socket_type))
}

pub(super) fn udp_associate_loop(
    client_relay: UdpSocket,
    protect_path: Option<String>,
    state: RuntimeState,
    running: Arc<AtomicBool>,
    attempt_token: Option<AttemptCorrelationId>,
) -> io::Result<()> {
    let mut udp_client_addr = None;
    let mut client_buffer = [0u8; 65_535];
    let mut upstream_buffer = [0u8; 65_535];
    let mut encode_buffer: Vec<u8> = Vec::with_capacity(65_535 + 24);
    let mut flow_state = HashMap::<UdpFlowKey, UdpFlowActivationState>::new();
    let mut expiry_schedule = UdpFlowExpirySchedule::default();
    let mut expired_flow_keys = Vec::new();
    let mut upstream_poll_scratch = UdpUpstreamPollScratch::default();
    let flow_limit = state.udp_flow_limit();

    while running.load(Ordering::Relaxed) {
        emit_due_direct_path_learning_timeouts(&state)?;
        let now = Instant::now();
        if expiry_schedule.is_due(now) {
            expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), now, &mut expired_flow_keys)?;
            expiry_schedule.refresh(flow_state.values().map(|entry| entry.last_used));
        }
        let mut made_progress = receive_and_forward_udp_client_packet(
            &client_relay,
            &mut client_buffer,
            &mut udp_client_addr,
            &mut flow_state,
            flow_limit,
            &state,
            protect_path.as_deref(),
            attempt_token.as_ref(),
        )?;

        if expiry_schedule.next_deadline().is_none() && !flow_state.is_empty() {
            // A new flow starts at the current time, so while a deadline is
            // cached it cannot expire earlier than that deadline. The only
            // transition that needs scheduling is empty to non-empty. Refreshes
            // can make a cached deadline stale-early, never stale-late; the due
            // scan then corrects it.
            expiry_schedule.refresh(flow_state.values().map(|entry| entry.last_used));
        }

        made_progress |= pump_udp_upstream_responses(
            &state,
            &client_relay,
            &mut upstream_buffer,
            &mut encode_buffer,
            &mut flow_state,
            &mut upstream_poll_scratch,
            protect_path.as_deref(),
        )?;

        if !made_progress {
            thread::sleep(Duration::from_millis(10));
        }
    }

    expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now(), &mut expired_flow_keys)?;
    Ok(())
}
