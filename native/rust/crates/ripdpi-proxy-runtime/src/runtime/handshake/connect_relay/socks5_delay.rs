use std::io;
use std::net::{SocketAddr, TcpStream};

use super::ConnectRelayError;
use super::delay::DelayConnect;
use super::reply::{SuccessReply, write_success_reply};
use super::routes::{acquire_same_sni_guard, connect_delayed_route, connect_provisional_route};
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::relay::{FirstOutboundRead, read_first_client_payload};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeTransportProtocol;

pub(super) fn connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: crate::runtime::types::RuntimeConnectionRoute,
    host_hint: Option<&str>,
) -> Result<DelayConnect, ConnectRelayError> {
    // Standard clients wait for CONNECT success before sending the payload.
    // The first SYN follows IP/default policy; payload policy gates application bytes.
    let original_targets = crate::runtime::routing::preferred_targets_for_transport(
        state,
        target,
        host_hint,
        RuntimeTransportProtocol::Tcp,
    );
    let initial_egress = state.destination_egress(target, host_hint, RuntimeTransportProtocol::Tcp);
    let mut connected = connect_provisional_route(target, state, route, host_hint.map(str::to_owned))?;
    let active = state
        .register_active_upstream_tcp_socket(&connected.upstream)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    write_success_reply(client, &SuccessReply::Socks5, Some(&connected.upstream))
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let payload = match read_first_client_payload(client, &connected.upstream, state.delayed_connect_buffer_size())
        .map_err(|err| ConnectRelayError::new(err, true))?
    {
        FirstOutboundRead::Payload(payload) => payload,
        FirstOutboundRead::ClientClosed => return Ok(DelayConnect::Closed),
        FirstOutboundRead::ServerFirst => return Ok(DelayConnect::Connected(Box::new(connected))),
    };
    let host = state.extract_relay_payload_host(&payload).or_else(|| host_hint.map(str::to_owned));
    let egress = state.destination_egress(target, host.as_deref(), RuntimeTransportProtocol::Tcp);
    if egress == DestinationEgress::Block {
        return Err(ConnectRelayError::new(
            io::Error::new(io::ErrorKind::PermissionDenied, "destination blocked by routing policy"),
            true,
        ));
    }
    if state.owned_stack_required_for_transparent_target(
        target,
        host.as_deref(),
        crate::runtime::adaptive::now_millis(),
    ) {
        let mut err = ConnectRelayError::owned_stack_required();
        err.mark_success_reply_sent();
        return Err(err);
    }
    let route = if state.delayed_route_matches_payload(connected.route.group_index, target, &payload, host.as_deref()) {
        connected.route.clone()
    } else {
        state
            .select_next_route(
                &connected.route,
                target,
                Some(&payload),
                host.as_deref(),
                RuntimeTransportProtocol::Tcp,
                RuntimeState::connect_failure_trigger(),
                true,
                None,
            )
            .ok_or_else(|| {
                ConnectRelayError::new(
                    io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"),
                    true,
                )
            })?
    };
    let payload_targets = crate::runtime::routing::preferred_targets_for_transport(
        state,
        target,
        host.as_deref(),
        RuntimeTransportProtocol::Tcp,
    );
    let payload_connect_required = state.route_uses_direct_syn_data_tfo(&route, Some(&payload));
    if route.group_index == connected.route.group_index
        && egress == initial_egress
        && host.as_deref() == host_hint
        && original_targets == payload_targets
        && !payload_connect_required
    {
        connected.seed_request = Some(payload);
        if host_hint.is_none() {
            connected.same_sni_guard = acquire_same_sni_guard(state, host.as_deref());
        }
    } else {
        drop(active);
        drop(connected);
        connected = connect_delayed_route(target, state, host, route, payload)?;
    }
    Ok(DelayConnect::Connected(Box::new(connected)))
}
