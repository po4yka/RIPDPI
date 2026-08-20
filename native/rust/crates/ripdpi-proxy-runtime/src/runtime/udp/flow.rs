use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use super::feedback::note_udp_flow_timeout_failure;
use super::flow_selection::try_advance_udp_preferred_target;
use super::session::UdpFlowSession;
use super::upstream_socks::UpstreamUdpSocks;
use super::{RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy};
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::state::{RuntimeState, UDP_FLOW_IDLE_TIMEOUT};
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub(super) struct UdpFlowKey {
    pub(super) client: SocketAddr,
    pub(super) target: SocketAddr,
    pub(super) host: Option<String>,
    pub(super) preserve_host_in_response: bool,
}

pub(super) struct UdpFlowActivationState {
    pub(super) session: UdpFlowSession,
    pub(super) last_used: Instant,
    pub(super) route: RuntimeConnectionRoute,
    pub(super) destination_egress: DestinationEgress,
    pub(super) socket_settings: RuntimeUdpSocketSettings,
    pub(super) packet_settings: RuntimeUdpPacketSettings,
    pub(super) source_rebind_policy: RuntimeUdpSourceRebindPolicy,
    pub(super) execution_family: Option<&'static str>,
    pub(super) attempt_token: Option<AttemptCorrelationId>,
    pub(super) host: Option<String>,
    pub(super) payload: Vec<u8>,
    pub(super) awaiting_response: bool,
    pub(super) upstream: UdpSocket,
    pub(super) quic_migrated: bool,
    pub(super) logical_target: SocketAddr,
    pub(super) current_target: SocketAddr,
    pub(super) target_candidates: Vec<SocketAddr>,
    pub(super) target_index: usize,
    pub(super) cache_host: bool,
    /// Live upstream SOCKS5 UDP ASSOCIATE session, present iff this flow's route
    /// group configures `ext_socks`. Owning it keeps the control TCP open for
    /// the flow lifetime (RFC 1928 ties the relay binding to that connection).
    pub(super) upstream_socks: Option<UpstreamUdpSocks>,
}

impl UdpFlowActivationState {
    /// `true` when datagrams to `upstream` must be RFC 1928-framed for a SOCKS5
    /// UDP relay rather than sent raw to the target.
    pub(super) fn socks_framed(&self) -> bool {
        self.upstream_socks.is_some()
    }
}

#[derive(Debug, Default)]
pub(super) struct UdpFlowExpirySchedule {
    next_deadline: Option<Instant>,
}

impl UdpFlowExpirySchedule {
    pub(super) fn refresh(&mut self, last_used: impl Iterator<Item = Instant>) {
        self.next_deadline = last_used.map(|last_used| last_used + UDP_FLOW_IDLE_TIMEOUT).min();
    }

    pub(super) fn next_deadline(&self) -> Option<Instant> {
        self.next_deadline
    }

    pub(super) fn is_due(&self, now: Instant) -> bool {
        self.next_deadline.is_some_and(|deadline| now >= deadline)
    }
}

pub(super) fn udp_flow_at_capacity<T>(
    flow_state: &HashMap<UdpFlowKey, T>,
    flow_key: &UdpFlowKey,
    flow_limit: usize,
) -> bool {
    RuntimeState::udp_flow_at_capacity(flow_state.contains_key(flow_key), flow_state.len(), flow_limit)
}

pub(super) fn expire_udp_flows(
    state: &RuntimeState,
    flow_state: &mut HashMap<UdpFlowKey, UdpFlowActivationState>,
    protect_path: Option<&str>,
    now: Instant,
    expired: &mut Vec<UdpFlowKey>,
) -> io::Result<()> {
    expired.clear();
    expired.extend(
        flow_state
            .iter()
            .filter(|(_, value)| now.duration_since(value.last_used) >= UDP_FLOW_IDLE_TIMEOUT)
            .map(|(key, _)| key.clone()),
    );

    for key in expired.iter() {
        let Some(mut entry) = flow_state.remove(key) else {
            continue;
        };
        if !entry.awaiting_response {
            continue;
        }
        if try_advance_udp_preferred_target(state, protect_path, &mut entry, now)? {
            flow_state.insert(key.clone(), entry);
            continue;
        }
        note_udp_flow_timeout_failure(state, &entry)?;
    }
    Ok(())
}
