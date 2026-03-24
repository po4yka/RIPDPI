use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_session::{encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply};

use crate::runtime_policy::{extract_host, group_requires_payload, route_matches_payload, TransportProtocol};

use super::super::state::RuntimeState;
use super::protocol_io::{send_success_reply, HandshakeKind};
use super::ws_tunnel::{
    run_ws_tunnel, should_ws_tunnel_fallback, should_ws_tunnel_first, try_ws_tunnel_fallback, WsTunnelResult,
};

enum DelayConnect {
    Immediate,
    Delayed { route: crate::runtime_policy::ConnectionRoute, payload: Vec<u8> },
    Closed,
}

/// Protocol-specific reply sent to the client on successful upstream connect.
pub(super) enum SuccessReply {
    /// Transparent proxy: no reply needed.
    None,
    /// SOCKS4: fixed success reply.
    Socks4,
    /// SOCKS5: reply includes the upstream bind address.
    Socks5,
    /// HTTP CONNECT: fixed 200 OK reply.
    HttpConnect,
}

/// Common connect-relay-WS fallback flow used by all protocol handlers except shadowsocks.
///
/// Handles:
/// 1. WS tunnel Always mode attempt
/// 2. Delay connect (read first request before connecting)
/// 3. Route selection + upstream relay
/// 4. WS tunnel Fallback mode on desync failure
///
/// Returns the raw error on failure -- callers handle protocol-specific error policy
/// (linger for transparent, swallow for SOCKS5/HTTP).
pub(super) fn connect_and_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    dc_host: Option<String>,
    reply: SuccessReply,
) -> io::Result<()> {
    // Always mode: try WS tunnel first
    if let Some(dc) = should_ws_tunnel_first(target, state) {
        write_success_reply(client, &reply, None)?;
        match run_ws_tunnel(client.try_clone()?, dc, target, state) {
            WsTunnelResult::Ok => return Ok(()),
            WsTunnelResult::Fallback { init_packet } => {
                let (upstream, route) =
                    super::super::routing::connect_target(target, state, Some(&init_packet), true, dc_host.clone())?;
                return super::super::relay::relay(
                    client.try_clone()?,
                    upstream,
                    state,
                    target,
                    route,
                    Some(init_packet),
                );
            }
            WsTunnelResult::FallbackNoInit => {
                // Init read failed; fall through to normal path
            }
        }
    }

    let ws_fallback_client = should_ws_tunnel_fallback(target, state).and_then(|_| client.try_clone().ok());
    let handshake_kind = match reply {
        SuccessReply::Socks4 => Some(HandshakeKind::Socks4),
        SuccessReply::Socks5 => Some(HandshakeKind::Socks5),
        SuccessReply::HttpConnect => Some(HandshakeKind::HttpConnect),
        SuccessReply::None => None,
    };

    let desync_result = match handshake_kind {
        Some(kind) => match maybe_delay_connect(client, state, target, kind)? {
            DelayConnect::Immediate => immediate_connect_relay(client, target, state, dc_host, &reply),
            DelayConnect::Delayed { route, payload } => {
                delayed_connect_relay(client, target, state, dc_host, route, payload)
            }
            DelayConnect::Closed => Ok(()),
        },
        // Transparent proxy: no delay_conn, always immediate
        None => immediate_connect_relay(client, target, state, dc_host, &reply),
    };

    match desync_result {
        Ok(()) => Ok(()),
        Err(err) => {
            // Fallback mode: try WS tunnel after desync failure
            if let Some(dc) = should_ws_tunnel_fallback(target, state) {
                if let Some(ref fallback_client) = ws_fallback_client {
                    if let Some(result) = try_ws_tunnel_fallback(fallback_client, target, dc, state) {
                        return result;
                    }
                }
            }
            Err(err)
        }
    }
}

fn immediate_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    dc_host: Option<String>,
    reply: &SuccessReply,
) -> io::Result<()> {
    let (upstream, route) = super::super::routing::connect_target(target, state, None, false, dc_host)?;
    write_success_reply(client, reply, Some(&upstream))?;
    super::super::relay::relay(client.try_clone()?, upstream, state, target, route, None)
}

fn delayed_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    dc_host: Option<String>,
    route: crate::runtime_policy::ConnectionRoute,
    payload: Vec<u8>,
) -> io::Result<()> {
    let host = extract_host(&state.config, &payload).or(dc_host);
    let (upstream, route) =
        super::super::routing::connect_target_with_route(target, state, route, Some(&payload), host)?;
    super::super::relay::relay(client.try_clone()?, upstream, state, target, route, Some(payload))
}

/// Write the protocol-appropriate success reply to the client.
fn write_success_reply(client: &mut TcpStream, reply: &SuccessReply, upstream: Option<&TcpStream>) -> io::Result<()> {
    use std::io::Write;
    match reply {
        SuccessReply::None => Ok(()),
        SuccessReply::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        SuccessReply::Socks5 => {
            let reply_addr = upstream
                .and_then(|u| u.local_addr().ok())
                .unwrap_or_else(|| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
            client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())
        }
        SuccessReply::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}

/// Maximum time to wait for the first request in delay_conn mode.
const DELAY_CONN_READ_TIMEOUT: Duration = Duration::from_secs(60);

fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    handshake: HandshakeKind,
) -> io::Result<DelayConnect> {
    if !state.config.network.delay_conn {
        return Ok(DelayConnect::Immediate);
    }
    let route = super::super::routing::select_route(state, target, None, None, true)?;
    let group = state
        .config
        .groups
        .get(route.group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    if !group_requires_payload(group) {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake)?;
    let Some(payload) = read_blocking_first_request(client, state.config.network.buffer_size)? else {
        return Ok(DelayConnect::Closed);
    };

    let route = if route_matches_payload(&state.config, route.group_index, target, &payload, TransportProtocol::Tcp) {
        route
    } else {
        let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
        let host = extract_host(&state.config, &payload);
        cache
            .select_next(
                &state.config,
                &route,
                target,
                Some(&payload),
                host.as_deref(),
                TransportProtocol::Tcp,
                0,
                true,
                None,
            )
            .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))?
    };

    Ok(DelayConnect::Delayed { route, payload })
}

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DELAY_CONN_READ_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size.max(16_384)];
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
