mod client_receive;
mod codec;
mod feedback;
mod flow;
mod flow_selection;
mod migration;
mod response_encode;
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
use ripdpi_proxy_runtime_adapter::model::config::udp_flow_limit;

use self::client_receive::receive_and_forward_udp_client_packet;
pub(crate) use self::codec::{encode_socks5_udp_packet, parse_socks5_udp_packet};
use self::flow::{expire_udp_flows, UdpFlowActivationState};
pub(crate) use self::sockets::build_udp_relay_sockets;
use self::upstream_pump::pump_udp_upstream_responses;
use super::adaptive::emit_due_direct_path_learning_timeouts;
use super::state::RuntimeState;

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
    let flow_limit = udp_flow_limit(&state.config);

    while running.load(Ordering::Relaxed) {
        emit_due_direct_path_learning_timeouts(&state)?;
        expire_udp_flows(&state, &mut flow_state, protect_path.as_deref(), Instant::now())?;
        let mut made_progress = receive_and_forward_udp_client_packet(
            &client_relay,
            &mut client_buffer,
            &mut udp_client_addr,
            &mut flow_state,
            flow_limit,
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
