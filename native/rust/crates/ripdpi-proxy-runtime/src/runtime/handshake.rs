mod connect_relay;
mod protocol_io;
#[cfg(all(test, not(feature = "loom")))]
mod tests;
mod ws_tunnel;

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use crate::sync::{Arc, AtomicBool, Ordering};
use ripdpi_proxy_runtime_adapter::model::protocol_auth::validate_http_connect_auth;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;
use ripdpi_proxy_runtime_adapter::model::session::{HttpConnectFailure, HttpConnectHead, S_ER_DENY};
use ripdpi_proxy_runtime_adapter::platform::handshake as handshake_platform;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use connect_relay::{ConnectPolicyRejection, ConnectRelayError, SuccessReply, connect_and_relay};
use protocol_io::{
    negotiate_socks5, peek_by, read_exact_by, read_http_connect_request, read_shadowsocks_request, read_socks4_request,
    read_socks5_request,
};

use super::state::{HANDSHAKE_TIMEOUT, RuntimeState};
use super::types::{RuntimeClientRequest, RuntimeProxyProtocolMode, RuntimeSessionError};

const OWNED_STACK_REQUIRED_HTTP_REPLY: &[u8] =
    b"HTTP/1.1 403 Forbidden\r\nX-RIPDPI-Reason: OWNED_STACK_REQUIRED\r\nContent-Length: 0\r\n\r\n";

pub(super) fn handle_client(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    let _ = client.set_write_timeout(Some(HANDSHAKE_TIMEOUT));
    // One absolute deadline covers the whole pre-relay handshake, so a peer
    // that sends a byte per timeout period cannot hold the client slot.
    let deadline = Instant::now() + HANDSHAKE_TIMEOUT;
    match state.proxy_protocol_mode() {
        RuntimeProxyProtocolMode::Transparent => handle_transparent(client, state),
        RuntimeProxyProtocolMode::HttpConnect => handle_http_connect(client, state, deadline),
        RuntimeProxyProtocolMode::Mixed { shadowsocks_enabled } => {
            handle_mixed(client, state, shadowsocks_enabled, deadline)
        }
        RuntimeProxyProtocolMode::BytePrefixed { shadowsocks_enabled } => {
            let mut first = [0u8; 1];
            read_first_byte(&mut client, &mut first, deadline)?;
            if shadowsocks_enabled {
                return handle_shadowsocks(client, state, first[0], deadline);
            }
            match first[0] {
                0x04 => handle_socks4(client, state, first[0], deadline),
                0x05 => handle_socks5(client, state, first[0], deadline),
                _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported proxy protocol")),
            }
        }
    }
}

/// Mixed inbound: one listener that speaks SOCKS5, SOCKS4 *and* HTTP CONNECT.
///
/// The first request byte selects the protocol. HTTP CONNECT is parsed from
/// the start of the stream by [`read_http_connect_request`], so its leading
/// byte must stay in the socket buffer — we therefore *peek* (non-consuming)
/// to classify, then only consume the byte for the SOCKS/shadowsocks paths,
/// whose handlers expect the version byte already read.
fn handle_mixed(
    mut client: TcpStream,
    state: &RuntimeState,
    shadowsocks_enabled: bool,
    deadline: Instant,
) -> io::Result<()> {
    let mut first = [0u8; 1];
    let peeked = peek_by(&mut client, &mut first, deadline)?;
    if peeked == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "empty mixed handshake"));
    }

    // 'C' (0x43) is the first byte of "CONNECT ...". Shadowsocks is a raw
    // byte-prefixed protocol and never combines with HTTP, so it short-circuits.
    if !shadowsocks_enabled && first[0] == b'C' {
        return handle_http_connect(client, state, deadline);
    }

    // Consume the classified byte and dispatch like the byte-prefixed path.
    let mut consumed = [0u8; 1];
    read_first_byte(&mut client, &mut consumed, deadline)?;
    if shadowsocks_enabled {
        return handle_shadowsocks(client, state, consumed[0], deadline);
    }
    match consumed[0] {
        0x04 => handle_socks4(client, state, consumed[0], deadline),
        0x05 => handle_socks5(client, state, consumed[0], deadline),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported proxy protocol")),
    }
}

/// Reads the protocol-selecting first byte under the handshake deadline.
fn read_first_byte(client: &mut TcpStream, first: &mut [u8; 1], deadline: Instant) -> io::Result<()> {
    let result = read_exact_by(client, first, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn handle_transparent(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let target = handshake_platform::original_destination(&client)
        .map_err(|e| io::Error::other(format!("get transparent proxy original destination: {e}")))?;
    let local = client.local_addr()?;
    if local == target {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "transparent proxy target resolves to the local listener",
        ));
    }

    let dc_host = state.telegram_dc_host_hint(target);

    match connect_and_relay(&mut client, target, state, dc_host, None, SuccessReply::None) {
        Ok(()) => Ok(()),
        Err(err) => {
            if matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut) {
                listener_platform::close_rejected_client(&client);
            }
            Err(err.into_io_error())
        }
    }
}

fn handle_socks4(mut client: TcpStream, state: &RuntimeState, version: u8, deadline: Instant) -> io::Result<()> {
    if state.proxy_auth_token().is_some() {
        client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "socks4 cannot authenticate"));
    }
    let request = match read_socks4_request(&mut client, version, deadline) {
        Ok(request) => request,
        Err(error) => {
            if error.kind() == io::ErrorKind::InvalidData {
                client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?;
            }
            return Err(error);
        }
    };
    let resolver = |host: &str| state.resolve_handshake_name(host, deadline);
    let parsed = state.parse_socks4_client_request(&request, resolver);
    match parsed {
        Ok(RuntimeClientRequest::Socks4Connect(target)) => {
            let dc_host = state.telegram_dc_host_hint(target.addr);
            let host_hint = target.host.or(dc_host);
            match connect_and_relay(&mut client, target.addr, state, host_hint, None, SuccessReply::Socks4) {
                Ok(()) => Ok(()),
                Err(err) => handle_socks4_connect_error(&mut client, err),
            }
        }
        Ok(_) => {
            client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
        Err(_) => {
            client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_socks5(mut client: TcpStream, state: &RuntimeState, version: u8, deadline: Instant) -> io::Result<()> {
    if !RuntimeState::is_socks5_version(version) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks version"));
    }
    let attempt_token = negotiate_socks5(&mut client, state.proxy_auth_token(), deadline)?;
    let request = read_socks5_request(&mut client, deadline)?;
    if request.get(3).copied().is_some_and(RuntimeState::is_socks5_resolved_domain_address_type)
        && (!client.peer_addr()?.ip().is_loopback() || !state.resolved_domain_targets_allowed())
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "RIPDPI resolved-domain address type is restricted to the local tunnel hop",
        ));
    }
    let resolver = |host: &str| state.resolve_handshake_name(host, deadline);

    match state.parse_socks5_client_request(&request, resolver) {
        Ok(RuntimeClientRequest::Socks5Connect(target)) => {
            let dc_host = state.telegram_dc_host_hint(target.addr);
            let host_hint = target.host.or(dc_host);
            match connect_and_relay(&mut client, target.addr, state, host_hint, attempt_token, SuccessReply::Socks5) {
                Ok(()) => Ok(()),
                Err(err) => handle_socks5_connect_error(&mut client, err),
            }
        }
        Ok(RuntimeClientRequest::Socks5UdpAssociate(requested_udp_source)) => {
            if !state.udp_associate_enabled() {
                let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
                client.write_all(
                    RuntimeState::encode_socks5_reply(RuntimeState::socks5_command_unsupported_code(), fail).as_bytes(),
                )?;
                return Ok(());
            }
            handle_socks5_udp_associate(client, state, attempt_token, requested_udp_source)
        }
        Ok(_) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(
                RuntimeState::encode_socks5_reply(RuntimeState::socks5_general_failure_code(), fail).as_bytes(),
            )?;
            Ok(())
        }
        Err(RuntimeSessionError { code }) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(RuntimeState::encode_socks5_reply(code, fail).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_http_connect(mut client: TcpStream, state: &RuntimeState, deadline: Instant) -> io::Result<()> {
    let Some(request) = read_http_connect_request(&mut client, deadline)? else {
        client.write_all(
            RuntimeState::encode_http_connect_failure(HttpConnectFailure::HeaderFieldsTooLarge).as_bytes(),
        )?;
        // Send FIN before closing with unread request bytes.
        client.shutdown(std::net::Shutdown::Write)?;
        return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
    };
    let Ok(head) = HttpConnectHead::parse(&request) else {
        client.write_all(RuntimeState::encode_http_connect_failure(HttpConnectFailure::BadRequest).as_bytes())?;
        return Ok(());
    };
    if let Some(token) = state.proxy_auth_token()
        && !validate_http_connect_auth(head, token)
    {
        let reply = b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"ripdpi\"\r\nContent-Length: 0\r\n\r\n";
        let _ = client.write_all(reply);
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "missing or invalid http proxy credentials"));
    }
    let resolver = |host: &str| state.resolve_handshake_name(host, deadline);
    match RuntimeState::parse_http_connect_client_request(head, resolver) {
        Ok(RuntimeClientRequest::HttpConnect(target)) => {
            let dc_host = state.telegram_dc_host_hint(target.addr);
            let host_hint = target.host.or(dc_host);
            match connect_and_relay(&mut client, target.addr, state, host_hint, None, SuccessReply::HttpConnect) {
                Ok(()) => Ok(()),
                Err(err) => handle_http_connect_error(&mut client, err),
            }
        }
        Ok(_) => {
            client.write_all(RuntimeState::encode_http_connect_failure(HttpConnectFailure::BadRequest).as_bytes())?;
            Ok(())
        }
        Err(RuntimeSessionError { code }) => {
            let failure = HttpConnectFailure::for_request_error(code);
            client.write_all(RuntimeState::encode_http_connect_failure(failure).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_shadowsocks(
    mut client: TcpStream,
    state: &RuntimeState,
    first_byte: u8,
    deadline: Instant,
) -> io::Result<()> {
    let resolver = |host: &str| state.resolve_handshake_name(host, deadline);
    let target = read_shadowsocks_request(&mut client, first_byte, state, resolver, deadline)?;
    connect_and_relay(&mut client, target.addr, state, target.host, None, SuccessReply::PlainAddress)
        .map_err(ConnectRelayError::into_io_error)
}

fn handle_socks5_udp_associate(
    mut client: TcpStream,
    state: &RuntimeState,
    attempt_token: Option<AttemptCorrelationId>,
    requested_udp_source: SocketAddr,
) -> io::Result<()> {
    let local_ip = client.local_addr()?.ip();
    let control_peer_ip = client.peer_addr()?.ip();
    let protect_path = state.handshake_protect_path();
    let relay = super::udp::build_udp_relay_sockets(local_ip)?;
    let reply_addr = relay.client.local_addr()?;
    client.write_all(RuntimeState::encode_socks5_reply(0, reply_addr).as_bytes())?;

    let running = Arc::new(AtomicBool::new(true));
    let worker_running = running.clone();
    let worker_state = state.clone();
    let worker_protect_path = protect_path;
    let worker = thread::Builder::new()
        .name("ripdpi-udp".into())
        .spawn(move || {
            super::udp::udp_associate_loop(
                relay.client,
                control_peer_ip,
                requested_udp_source,
                worker_protect_path,
                worker_state,
                worker_running,
                attempt_token,
            )
        })
        .map_err(|err| io::Error::other(format!("failed to spawn UDP relay thread: {err}")))?;

    let _ = client.set_read_timeout(Some(Duration::from_millis(250)));
    let mut buffer = [0u8; 64];
    loop {
        match client.read(&mut buffer) {
            Ok(0) => break,
            Ok(_) => {}
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(_) => break,
        }
        if !running.load(Ordering::Relaxed) || state.shutdown_requested() {
            break;
        }
    }

    running.store(false, Ordering::Relaxed);
    worker.join().map_err(|_| io::Error::other("udp relay thread panicked"))?
}

/// Rejects proxy targets that would reach the device itself instead of the network:
/// the listener's own address (a self-connect loop) for every peer, and loopback,
/// unspecified or link-local targets for a peer that is not on this device, so an
/// Allow-LAN client cannot reach services that trust loopback (RFC 9110 section 9.3.6).
#[cfg(test)]
fn reject_forbidden_target(client: &TcpStream, target: SocketAddr, state: &RuntimeState) -> io::Result<()> {
    match forbidden_target_reason(client.peer_addr()?, client.local_addr()?, target, state) {
        Some(reason) => Err(io::Error::new(io::ErrorKind::PermissionDenied, reason)),
        None => Ok(()),
    }
}

fn forbidden_target_reason(
    peer: SocketAddr,
    local: SocketAddr,
    target: SocketAddr,
    state: &RuntimeState,
) -> Option<&'static str> {
    let target_ip = target.ip().to_canonical();
    if state.listener_covers_target(local, target) {
        return Some("proxy target is the proxy listener itself");
    }
    is_device_local_target_for(peer.ip(), target_ip).then_some("proxy target is device-local and the client is not")
}

/// True when a client at `peer_ip`, which is not on this device, targets a loopback,
/// unspecified or link-local address of the device. Shared by TCP and UDP targets.
pub(super) fn is_device_local_target_for(peer_ip: IpAddr, target_ip: IpAddr) -> bool {
    if peer_ip.to_canonical().is_loopback() {
        return false;
    }
    match target_ip.to_canonical() {
        IpAddr::V4(ip) => ip.is_loopback() || ip.is_unspecified() || ip.is_link_local(),
        IpAddr::V6(ip) => ip.is_loopback() || ip.is_unspecified() || ip.is_unicast_link_local(),
    }
}

fn handle_socks4_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    if !err.success_reply_sent() {
        client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?;
    }
    Err(err.into_io_error())
}

fn handle_socks5_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    let success_reply_sent = err.success_reply_sent();
    let policy_rejection = err.policy_rejection();
    let error = err.into_io_error();
    if !success_reply_sent {
        let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        let code =
            if policy_rejection.is_some() { S_ER_DENY } else { RuntimeState::socks5_reply_code_for_error(&error) };
        client.write_all(RuntimeState::encode_socks5_reply(code, fail).as_bytes())?;
    }
    Err(error)
}

fn handle_http_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    if !err.success_reply_sent() {
        let failure = match err.policy_rejection() {
            Some(ConnectPolicyRejection::OwnedStackRequired) => {
                client.write_all(OWNED_STACK_REQUIRED_HTTP_REPLY)?;
                return Err(err.into_io_error());
            }
            Some(ConnectPolicyRejection::RulesetDenied) => HttpConnectFailure::Forbidden,
            None => HttpConnectFailure::for_connect_error(err.kind()),
        };
        client.write_all(RuntimeState::encode_http_connect_failure(failure).as_bytes())?;
    }
    Err(err.into_io_error())
}
