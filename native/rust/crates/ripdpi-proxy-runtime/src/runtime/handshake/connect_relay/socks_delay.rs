use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};

use super::ConnectRelayError;
use super::delay::DelayConnect;
use super::reply::{SuccessReply, write_success_reply};
use super::routes::{acquire_same_sni_guard, connect_delayed_route, connect_provisional_route};
use crate::runtime::destination_routing::DestinationEgress;
use crate::runtime::relay::{FirstOutboundRead, read_first_client_payload};
use crate::runtime::state::RouteConnectPolicy;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeTransportProtocol;

pub(super) fn connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: crate::runtime::types::RuntimeConnectionRoute,
    host_hint: Option<&str>,
    reply: &SuccessReply,
) -> Result<DelayConnect, ConnectRelayError> {
    connect_with(client, state, target, route, host_hint, reply, |client, upstream, buffer_size| {
        let mut watched_upstream = Some(upstream);
        loop {
            match read_first_client_payload(
                client,
                watched_upstream,
                buffer_size,
                state.destination_policy_may_need_host(),
            )? {
                FirstOutboundRead::ServerFirst if !state.destination_policy_may_need_host() => {
                    return Ok(FirstOutboundRead::ServerFirst);
                }
                FirstOutboundRead::ServerFirst => {
                    // A greeting does not release the first client write from hostname policy.
                    let mut banner = vec![0; buffer_size];
                    let mut reader = upstream;
                    let count = reader.read(&mut banner)?;
                    if count == 0 {
                        client.shutdown(Shutdown::Write)?;
                        watched_upstream = None;
                    } else {
                        client.write_all(&banner[..count])?;
                        state.note_application_traffic(0, count as u64);
                    }
                }
                payload => return Ok(payload),
            }
        }
    })
}

pub(super) fn connect_with(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: crate::runtime::types::RuntimeConnectionRoute,
    host_hint: Option<&str>,
    reply: &SuccessReply,
    mut read_payload: impl FnMut(&mut TcpStream, &TcpStream, usize) -> io::Result<FirstOutboundRead<Vec<u8>>>,
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
    let provisional_route =
        crate::runtime::routing::select_route(state, target, None, host_hint, false).unwrap_or_else(|_| route.clone());
    let mut connected = connect_provisional_route(target, state, provisional_route, host_hint.map(str::to_owned))?;
    let mut active = state
        .register_active_upstream_tcp_socket(&connected.upstream)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    write_success_reply(client, reply, Some(&connected.upstream)).map_err(|err| ConnectRelayError::new(err, false))?;
    let mut read = read_payload(client, &connected.upstream, state.delayed_connect_buffer_size())
        .map_err(|err| ConnectRelayError::new(err, true))?;
    if read == FirstOutboundRead::DeferredIdle {
        // The deferred socket sent no SYN, so replacing it with a plain connect is free on the
        // wire and lets a server-first peer speak. One replacement per flow: it is not deferred.
        drop(active);
        let old_cap_guard = connected.cap_guard.take();
        drop(old_cap_guard);
        let (upstream, route, cap_guard) = crate::runtime::routing::reconnect_target_without_tfo(
            target,
            state,
            connected.route.clone(),
            host_hint.map(str::to_owned),
            None,
        )
        .map_err(|err| ConnectRelayError::new(err, true))?;
        connected.upstream = upstream;
        connected.route = route;
        connected.cap_guard = cap_guard;
        active = state
            .register_active_upstream_tcp_socket(&connected.upstream)
            .map_err(|err| ConnectRelayError::new(err, true))?;
        read = read_payload(client, &connected.upstream, state.delayed_connect_buffer_size())
            .map_err(|err| ConnectRelayError::new(err, true))?;
    }
    let payload = match read {
        FirstOutboundRead::Payload(payload) => payload,
        FirstOutboundRead::ClientClosed | FirstOutboundRead::ServerFirst => {
            return Ok(DelayConnect::Connected(Box::new(connected)));
        }
        FirstOutboundRead::DeferredIdle => {
            return Err(ConnectRelayError::new(
                io::Error::new(io::ErrorKind::TimedOut, "replacement upstream is still deferred"),
                true,
            ));
        }
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
    let route = if state.delayed_route_matches_payload(route.group_index, target, &payload, host.as_deref()) {
        route
    } else {
        state
            .select_next_route(
                &route,
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
    // The provisional policy may differ from the payload policy only in the TFO bit, which
    // depends on the payload.
    let compatible_connect_policy = matches!(
        (
            state.route_provisional_connect_policy(&connected.route, initial_egress),
            state.route_connect_policy(route.group_index, Some(&payload), true, egress),
        ),
        (Some(initial), Some(selected)) if RouteConnectPolicy { tfo_enabled: selected.tfo_enabled, ..initial.clone() } == selected
    );
    // A deferred TFO socket has sent no SYN: reuse it only when the first write may carry
    // SYN data for this route, otherwise drop it (free on the wire) and reconnect.
    let deferred = connected.upstream.peer_addr().is_err_and(|err| err.kind() == io::ErrorKind::NotConnected);
    let reusable_socket = !deferred || state.route_uses_direct_syn_data_tfo(&route, Some(&payload));
    if compatible_connect_policy && egress == initial_egress && original_targets == payload_targets && reusable_socket {
        connected.route = route;
        connected.seed_request = Some(payload);
        if host.as_deref() != host_hint {
            drop(connected.same_sni_guard.take());
            connected.same_sni_guard = acquire_same_sni_guard(state, host.as_deref());
        }
    } else {
        drop(active);
        drop(connected);
        connected = connect_delayed_route(target, state, host, route, payload)?;
    }
    Ok(DelayConnect::Connected(Box::new(connected)))
}
