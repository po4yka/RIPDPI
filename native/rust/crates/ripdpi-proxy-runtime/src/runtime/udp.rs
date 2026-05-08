mod client_receive;
mod feedback;
mod flow;
mod flow_selection;
mod migration;
mod observations;
mod session;
mod sockets;
mod upstream_pump;

#[cfg(test)]
mod tests;

use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use crate::sync::{Arc, AtomicBool, Ordering};
use ripdpi_proxy_runtime_adapter::model::session as session_model;

use self::client_receive::receive_and_forward_udp_client_packet;
use self::flow::{expire_udp_flows, UdpFlowActivationState};
pub(crate) use self::sockets::build_udp_relay_sockets;
use self::upstream_pump::pump_udp_upstream_responses;
use super::adaptive::emit_due_direct_path_learning_timeouts;
use super::state::RuntimeState;
pub(crate) use session_model::encode_socks5_udp_packet;

pub(crate) fn parse_socks5_udp_packet<'a>(packet: &'a [u8], state: &RuntimeState) -> Option<(SocketAddr, &'a [u8])> {
    state.parse_socks5_udp_packet(packet, |host, socket_type| {
        debug_assert_eq!(socket_type, session_model::SocketType::Datagram);
        super::handshake::resolve_name(host, socket_type, state)
    })
}

pub(super) fn udp_associate_loop(
    client_relay: UdpSocket,
    protect_path: Option<String>,
    state: RuntimeState,
    running: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut udp_client_addr = None;
    let mut client_buffer = [0u8; 65_535];
    let mut upstream_buffer = [0u8; 65_535];
    let mut flow_state = HashMap::<(SocketAddr, SocketAddr), UdpFlowActivationState>::new();
    let flow_limit = state.udp_flow_limit;
    let payload_classifier = state.udp_payload_classifier();

    while running.load(Ordering::Relaxed) {
        emit_due_direct_path_learning_timeouts(&state)?;
        expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now())?;
        let mut made_progress = receive_and_forward_udp_client_packet(
            &client_relay,
            &mut client_buffer,
            &mut udp_client_addr,
            &mut flow_state,
            flow_limit,
            &payload_classifier,
            &state,
            protect_path.as_deref(),
        )?;

        made_progress |= pump_udp_upstream_responses(
            &state,
            &client_relay,
            &mut upstream_buffer,
            &mut flow_state,
            protect_path.as_deref(),
        )?;

        if !made_progress {
            thread::sleep(Duration::from_millis(10));
        }
    }

    expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now())?;
    Ok(())
}
