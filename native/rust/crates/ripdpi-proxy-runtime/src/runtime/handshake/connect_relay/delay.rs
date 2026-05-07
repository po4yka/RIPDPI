use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::{delayed_connect_enabled, runtime_buffer_size, DETECT_CONNECT};
use ripdpi_proxy_runtime_adapter::protocol_payload;
use ripdpi_runtime_decision_ports::policy::{
    extract_host, group_requires_payload, route_matches_payload, TransportProtocol,
};

use super::super::super::state::RuntimeState;
use super::super::protocol_io::{send_success_reply, HandshakeKind};
use super::ConnectRelayError;

pub(super) enum DelayConnect {
    Immediate,
    Delayed { route: ripdpi_runtime_decision_ports::policy::ConnectionRoute, payload: Vec<u8> },
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
    if !delayed_connect_enabled(&state.config) {
        return Ok(DelayConnect::Immediate);
    }
    let route = super::super::super::routing::select_route(state, target, None, None, true)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let group = state.config.groups.get(route.group_index).ok_or_else(|| {
        ConnectRelayError::new(io::Error::new(io::ErrorKind::NotFound, "missing desync group"), false)
    })?;
    if !group_requires_payload(group) {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake).map_err(|err| ConnectRelayError::new(err, false))?;
    let Some(payload) = read_blocking_first_request(client, runtime_buffer_size(&state.config))
        .map_err(|err| ConnectRelayError::new(err, true))?
    else {
        return Ok(DelayConnect::Closed);
    };

    let host = extract_host(&state.config, &payload).or_else(|| host_hint.map(ToOwned::to_owned));
    let route = if delayed_route_matches(&state.config, route.group_index, target, &payload, host.as_deref()) {
        route
    } else {
        state
            .policy
            .select_next(
                &state.config,
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

fn delayed_route_matches(
    config: &ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    host_hint: Option<&str>,
) -> bool {
    if route_matches_payload(config, group_index, target, payload, TransportProtocol::Tcp) {
        return true;
    }

    let Some(host) = host_hint else {
        return false;
    };
    let Some(group) = config.groups.get(group_index) else {
        return false;
    };
    if !group.matches.filters.hosts_match(host) {
        return false;
    }

    protocol_payload::group_accepts_any_or_non_http_tls(group)
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
