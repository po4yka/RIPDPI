use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::{
    delayed_route_matches_payload_with, route_requires_delay_payload_with, DETECT_CONNECT,
};
use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, TransportProtocol};
use ripdpi_proxy_runtime_adapter::model::session::extract_payload_host_with;

use super::super::super::state::RuntimeState;
use super::super::protocol_io::{send_success_reply, HandshakeKind};
use super::ConnectRelayError;

pub(super) enum DelayConnect {
    Immediate,
    Delayed { route: ConnectionRoute, payload: Vec<u8> },
    Closed,
}

/// Maximum time to wait for the first request in delay_conn mode.
const DELAY_CONN_READ_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    host_hint: Option<&str>,
    handshake: HandshakeKind,
) -> Result<DelayConnect, ConnectRelayError> {
    let settings = state.delayed_connect_settings;
    if !settings.enabled {
        return Ok(DelayConnect::Immediate);
    }
    let route = super::super::super::routing::select_route(state, target, None, None, true)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let requires_delay = route_requires_delay_payload_with(&state.route_payload_matcher, route.group_index)
        .ok_or_else(|| {
            ConnectRelayError::new(io::Error::new(io::ErrorKind::NotFound, "missing desync group"), false)
        })?;
    if !requires_delay {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake).map_err(|err| ConnectRelayError::new(err, false))?;
    let Some(payload) =
        read_blocking_first_request(client, settings.buffer_size).map_err(|err| ConnectRelayError::new(err, true))?
    else {
        return Ok(DelayConnect::Closed);
    };

    let host =
        extract_payload_host_with(&state.relay_host_extractor, &payload).or_else(|| host_hint.map(ToOwned::to_owned));
    let route = if delayed_route_matches_payload_with(
        &state.route_payload_matcher,
        route.group_index,
        target,
        &payload,
        host.as_deref(),
    ) {
        route
    } else {
        state
            .policy()
            .select_next(
                &route,
                target,
                Some(&payload),
                host.as_deref(),
                TransportProtocol::Tcp,
                DETECT_CONNECT,
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

    Ok(DelayConnect::Delayed { route, payload })
}

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DELAY_CONN_READ_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) => Err(err),
    };
    client.set_read_timeout(original_timeout)?;
    result
}
