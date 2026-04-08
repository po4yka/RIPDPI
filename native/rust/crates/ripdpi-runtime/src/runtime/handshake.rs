mod connect_relay;
mod protocol_io;
#[cfg(test)]
mod tests;
mod ws_tunnel;

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::thread;
use std::time::Duration;

use crate::sync::{Arc, AtomicBool, Ordering};
use crate::{platform, runtime_policy::extract_host};
use ripdpi_session::{
    encode_socks4_reply, encode_socks5_reply, parse_http_connect_request, parse_socks4_request, parse_socks5_request,
    ClientRequest, SessionConfig, SessionError, SocketType, S_ER_CMD, S_ER_GEN, S_VER5,
};
use socket2::SockRef;

use connect_relay::{connect_and_relay, ConnectRelayError, SuccessReply};
use protocol_io::{
    negotiate_socks5, read_http_connect_request, read_shadowsocks_request, read_socks4_request, read_socks5_request,
};
use ws_tunnel::{detect_telegram_dc, telegram_dc_host};

pub(super) use protocol_io::resolve_name;

use super::state::{RuntimeState, HANDSHAKE_TIMEOUT};

pub(super) fn handle_client(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    let _ = client.set_write_timeout(Some(HANDSHAKE_TIMEOUT));
    if state.config.network.transparent {
        return handle_transparent(client, state);
    }
    if state.config.network.http_connect {
        return handle_http_connect(client, state);
    }

    let mut first = [0u8; 1];
    client.read_exact(&mut first)?;
    if state.config.network.shadowsocks {
        return handle_shadowsocks(client, state, first[0]);
    }
    match first[0] {
        0x04 => handle_socks4(client, state, first[0]),
        0x05 => handle_socks5(client, state, first[0]),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported proxy protocol")),
    }
}

fn handle_transparent(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let target = platform::original_dst(&client)
        .map_err(|e| io::Error::other(format!("get transparent proxy original destination: {e}")))?;
    let local = client.local_addr()?;
    if local == target {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "transparent proxy target resolves to the local listener",
        ));
    }

    let dc_host = detect_telegram_dc(target).map(|dc| {
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_telegram_dc_detected(target, dc);
        }
        telegram_dc_host(dc)
    });

    match connect_and_relay(&mut client, target, state, dc_host, SuccessReply::None) {
        Ok(()) => Ok(()),
        Err(err) => {
            if matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut) {
                let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
            }
            Err(err.into_io_error())
        }
    }
}

fn handle_socks4(mut client: TcpStream, state: &RuntimeState, version: u8) -> io::Result<()> {
    let request = read_socks4_request(&mut client, version)?;
    let session = SessionConfig { resolve: state.config.network.resolve, ipv6: state.config.network.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, state);
    let parsed = parse_socks4_request(&request, session, &resolver);
    match parsed {
        Ok(ClientRequest::Socks4Connect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });
            match connect_and_relay(&mut client, target.addr, state, dc_host, SuccessReply::Socks4) {
                Ok(()) => Ok(()),
                Err(err) => handle_socks4_connect_error(&mut client, err),
            }
        }
        Ok(_) => {
            client.write_all(encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
        Err(_) => {
            client.write_all(encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_socks5(mut client: TcpStream, state: &RuntimeState, version: u8) -> io::Result<()> {
    if version != S_VER5 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks version"));
    }
    negotiate_socks5(&mut client, state.config.network.listen.auth_token.as_deref())?;
    let request = read_socks5_request(&mut client)?;
    let session = SessionConfig { resolve: state.config.network.resolve, ipv6: state.config.network.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, state);

    match parse_socks5_request(&request, SocketType::Stream, session, &resolver) {
        Ok(ClientRequest::Socks5Connect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });
            match connect_and_relay(&mut client, target.addr, state, dc_host, SuccessReply::Socks5) {
                Ok(()) => Ok(()),
                Err(err) => handle_socks5_connect_error(&mut client, err),
            }
        }
        Ok(ClientRequest::Socks5UdpAssociate(_target)) => {
            if !state.config.network.udp {
                let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
                client.write_all(encode_socks5_reply(S_ER_CMD, fail).as_bytes())?;
                return Ok(());
            }
            handle_socks5_udp_associate(client, state)
        }
        Ok(_) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(S_ER_GEN, fail).as_bytes())?;
            Ok(())
        }
        Err(SessionError { code }) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(code, fail).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_http_connect(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let request = read_http_connect_request(&mut client)?;
    if let Some(token) = state.config.network.listen.auth_token.as_deref() {
        if !protocol_io::validate_http_proxy_auth(&request, token) {
            let reply = b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"ripdpi\"\r\nContent-Length: 0\r\n\r\n";
            let _ = client.write_all(reply);
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "missing or invalid http proxy credentials"));
        }
    }
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, state);
    match parse_http_connect_request(&request, &resolver) {
        Ok(ClientRequest::HttpConnect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });
            match connect_and_relay(&mut client, target.addr, state, dc_host, SuccessReply::HttpConnect) {
                Ok(()) => Ok(()),
                Err(err) => handle_http_connect_error(&mut client, err),
            }
        }
        _ => {
            use ripdpi_session::encode_http_connect_reply;
            client.write_all(encode_http_connect_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_shadowsocks(mut client: TcpStream, state: &RuntimeState, first_byte: u8) -> io::Result<()> {
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, state);
    let (target, first_request) = read_shadowsocks_request(&mut client, first_byte, &state.config, resolver)?;
    let host = extract_host(&state.config, &first_request);
    let payload = if first_request.is_empty() { None } else { Some(first_request.as_slice()) };
    let (upstream, route) = super::routing::connect_target(target, state, payload, false, host)?;
    super::relay::relay(
        client,
        upstream,
        state,
        target,
        route,
        if first_request.is_empty() { None } else { Some(first_request) },
    )
}

fn handle_socks5_udp_associate(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let local_ip = client.local_addr()?.ip();
    let relay = super::udp::build_udp_relay_sockets(local_ip, state.config.process.protect_path.as_deref())?;
    let reply_addr = relay.client.local_addr()?;
    client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;

    let running = Arc::new(AtomicBool::new(true));
    let worker_running = running.clone();
    let worker_state = state.clone();
    let worker_protect_path = state.config.process.protect_path.clone();
    let worker = thread::Builder::new()
        .name("ripdpi-udp".into())
        .spawn(move || super::udp::udp_associate_loop(relay.client, worker_protect_path, worker_state, worker_running))
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
        if !running.load(Ordering::Relaxed) {
            break;
        }
    }

    running.store(false, Ordering::Relaxed);
    worker.join().map_err(|_| io::Error::other("udp relay thread panicked"))?
}

fn handle_socks4_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    if !err.success_reply_sent() {
        client.write_all(encode_socks4_reply(false).as_bytes())?;
    }
    Err(err.into_io_error())
}

fn handle_socks5_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    if !err.success_reply_sent() {
        let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        client.write_all(encode_socks5_reply(S_ER_GEN, fail).as_bytes())?;
    }
    Err(err.into_io_error())
}

fn handle_http_connect_error(client: &mut TcpStream, err: ConnectRelayError) -> io::Result<()> {
    if !err.success_reply_sent() {
        use ripdpi_session::encode_http_connect_reply;
        client.write_all(encode_http_connect_reply(false).as_bytes())?;
    }
    Err(err.into_io_error())
}
