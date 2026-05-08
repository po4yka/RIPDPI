use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::udp_flow_at_capacity as projected_udp_flow_at_capacity;
use ripdpi_proxy_runtime_adapter::model::config::{
    UdpGroupPacketSettings, UdpGroupSocketSettings, UdpSourceRebindPolicy,
};
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;

use super::feedback::note_udp_flow_timeout_failure;
use super::flow_selection::try_advance_udp_preferred_target;
use super::session::UdpFlowSession;
use crate::runtime::state::{RuntimeState, UDP_FLOW_IDLE_TIMEOUT};

pub(super) struct UdpFlowActivationState {
    pub(super) session: UdpFlowSession,
    pub(super) last_used: Instant,
    pub(super) route: ConnectionRoute,
    pub(super) socket_settings: UdpGroupSocketSettings,
    pub(super) packet_settings: UdpGroupPacketSettings,
    pub(super) source_rebind_policy: UdpSourceRebindPolicy,
    pub(super) host: Option<String>,
    pub(super) payload: Vec<u8>,
    pub(super) awaiting_response: bool,
    pub(super) upstream: UdpSocket,
    pub(super) quic_migrated: bool,
    pub(super) current_target: SocketAddr,
    pub(super) target_candidates: Vec<SocketAddr>,
    pub(super) target_index: usize,
    pub(super) cache_host: bool,
}

pub(super) fn udp_flow_at_capacity<T>(
    flow_state: &HashMap<(SocketAddr, SocketAddr), T>,
    flow_key: (SocketAddr, SocketAddr),
    flow_limit: usize,
) -> bool {
    projected_udp_flow_at_capacity(flow_state.contains_key(&flow_key), flow_state.len(), flow_limit)
}

pub(super) fn expire_udp_flows(
    state: &RuntimeState,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    protect_path: Option<&str>,
    now: Instant,
) -> io::Result<()> {
    let expired = flow_state
        .iter()
        .filter(|(_, value)| now.duration_since(value.last_used) >= UDP_FLOW_IDLE_TIMEOUT)
        .map(|(key, _)| *key)
        .collect::<Vec<_>>();

    for (client_addr, target) in expired {
        let Some(mut entry) = flow_state.remove(&(client_addr, target)) else {
            continue;
        };
        if !entry.awaiting_response {
            continue;
        }
        if try_advance_udp_preferred_target(state, protect_path, &mut entry, now)? {
            flow_state.insert((client_addr, target), entry);
            continue;
        }
        note_udp_flow_timeout_failure(state, &entry)?;
    }
    Ok(())
}
