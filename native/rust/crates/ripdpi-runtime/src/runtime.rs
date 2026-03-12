use std::io::{self, Read, Write};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::platform;
use crate::process;
use crate::runtime_policy::{
    extract_host, extract_host_info, group_requires_payload, route_matches_payload, select_initial_group,
    select_next_group, ConnectionRoute, HostSource, RouteAdvance, RuntimeCache, TransportProtocol,
};
use crate::{current_runtime_telemetry, RuntimeTelemetrySink};
use ciadpi_config::{
    DesyncGroup, QuicInitialMode, RuntimeConfig, TcpChainStepKind, DETECT_CONNECT, DETECT_HTTP_LOCAT, DETECT_TLS_ERR,
    DETECT_TORST,
};
use ciadpi_desync::{
    activation_filter_matches, build_fake_packet, build_hostfake_bytes, plan_tcp, plan_udp, resolve_hostfake_span,
    ActivationContext, ActivationTransport, DesyncAction, DesyncPlan,
};
use ciadpi_session::{
    detect_response_trigger, encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply,
    parse_http_connect_request, parse_socks4_request, parse_socks5_request, ClientRequest, SessionConfig, SessionError,
    OutboundProgress, SessionState, SocketType, TriggerEvent, S_ATP_I4, S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE, S_CMD_CONN, S_ER_CMD,
    S_ER_CONN, S_ER_GEN, S_VER5,
};
use mio::net::TcpListener as MioTcpListener;
use mio::{Events, Interest, Poll, Token};
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

const LISTENER: Token = Token(0);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const DESYNC_SEED_BASE: u32 = 7;

#[derive(Clone)]
struct RuntimeState {
    config: Arc<RuntimeConfig>,
    cache: Arc<Mutex<RuntimeCache>>,
    active_clients: Arc<AtomicUsize>,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
}

#[derive(Debug, Clone)]
struct UdpFlowActivationState {
    session: SessionState,
    last_used: Instant,
}

pub fn run_proxy(config: RuntimeConfig) -> io::Result<()> {
    let listener = create_listener(&config)?;
    run_proxy_with_listener(config, listener)
}

pub fn create_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    build_listener(config)
}

pub fn run_proxy_with_listener(config: RuntimeConfig, listener: TcpListener) -> io::Result<()> {
    let mut config = config;
    if config.default_ttl == 0 {
        config.default_ttl = platform::detect_default_ttl()?;
    }
    let cache = RuntimeCache::load(&config);
    let state = RuntimeState {
        config: Arc::new(config),
        cache: Arc::new(Mutex::new(cache)),
        active_clients: Arc::new(AtomicUsize::new(0)),
        telemetry: current_runtime_telemetry(),
    };
    let _cleanup = RuntimeCleanup { config: state.config.clone(), cache: state.cache.clone() };
    listener.set_nonblocking(true)?;
    let mut listener = MioTcpListener::from_std(listener);
    let mut poll = Poll::new()?;
    poll.registry().register(&mut listener, LISTENER, Interest::READABLE)?;
    let mut events = Events::with_capacity(256);
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(
            SocketAddr::new(state.config.listen.listen_ip, state.config.listen.listen_port),
            state.config.max_open as usize,
            state.config.groups.len(),
        );
    }
    if let Ok(mut cache) = state.cache.lock() {
        flush_autolearn_updates(&state, &mut cache);
    }

    let result = loop {
        if process::shutdown_requested() {
            break Ok(());
        }
        poll.poll(&mut events, Some(Duration::from_millis(250)))?;
        for event in &events {
            if event.token() != LISTENER {
                continue;
            }
            loop {
                match listener.accept() {
                    Ok((stream, _addr)) => {
                        let state = state.clone();
                        let client = mio_to_std_stream(stream);
                        client.set_nonblocking(false)?;
                        let Some(_slot) =
                            ClientSlotGuard::acquire(state.active_clients.clone(), state.config.max_open as usize)
                        else {
                            let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
                            drop(client);
                            continue;
                        };
                        if let Some(telemetry) = &state.telemetry {
                            telemetry.on_client_accepted();
                        }
                        thread::Builder::new()
                            .name("ripdpi-client".into())
                            .spawn(move || {
                                let _slot = _slot;
                                let result = handle_client(client, &state);
                                if let Err(err) = &result {
                                    log::error!("ciadpi client error: {err}");
                                    if let Some(telemetry) = &state.telemetry {
                                        telemetry.on_client_error(err);
                                    }
                                }
                                if let Some(telemetry) = &state.telemetry {
                                    telemetry.on_client_finished();
                                }
                            })
                            .expect("failed to spawn client thread");
                    }
                    Err(err) if err.kind() == io::ErrorKind::WouldBlock => break,
                    Err(err) => return Err(err),
                }
            }
        }
    };
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_stopped();
    }
    result
}

struct RuntimeCleanup {
    config: Arc<RuntimeConfig>,
    cache: Arc<Mutex<RuntimeCache>>,
}

impl Drop for RuntimeCleanup {
    fn drop(&mut self) {
        let Ok(cache) = self.cache.lock() else {
            return;
        };
        let _ = cache.dump_stdout_groups(&self.config, std::io::stdout());
    }
}

struct ClientSlotGuard {
    active: Arc<AtomicUsize>,
}

impl ClientSlotGuard {
    fn acquire(active: Arc<AtomicUsize>, limit: usize) -> Option<Self> {
        loop {
            let current = active.load(Ordering::Relaxed);
            if current >= limit {
                return None;
            }
            if active.compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed).is_ok() {
                return Some(Self { active });
            }
        }
    }
}

impl Drop for ClientSlotGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    let listen_addr = SocketAddr::new(config.listen.listen_ip, config.listen.listen_port);
    let domain = match listen_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_reuse_address(true)?;
    socket.bind(&SockAddr::from(listen_addr))?;
    socket.listen(1024)?;
    let listener: TcpListener = socket.into();
    listener.set_nonblocking(true)?;
    Ok(listener)
}

fn handle_client(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    client.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    client.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
    if state.config.transparent {
        return handle_transparent(client, state);
    }
    if state.config.http_connect {
        return handle_http_connect(client, state);
    }

    let mut first = [0u8; 1];
    client.read_exact(&mut first)?;
    if state.config.shadowsocks {
        return handle_shadowsocks(client, state, first[0]);
    }
    match first[0] {
        0x04 => handle_socks4(client, state, first[0]),
        0x05 => handle_socks5(client, state, first[0]),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported proxy protocol")),
    }
}

fn handle_transparent(client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let target = platform::original_dst(&client)?;
    let local = client.local_addr()?;
    if local == target {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "transparent proxy target resolves to the local listener",
        ));
    }

    match connect_target(target, state, None, false, None) {
        Ok((upstream, route)) => relay(client, upstream, state, target, route, None),
        Err(err) => {
            if matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut) {
                let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
            }
            Err(err)
        }
    }
}

fn handle_socks4(mut client: TcpStream, state: &RuntimeState, version: u8) -> io::Result<()> {
    let request = read_socks4_request(&mut client, version)?;
    let session = SessionConfig { resolve: state.config.resolve, ipv6: state.config.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);
    let parsed = parse_socks4_request(&request, session, &resolver);
    match parsed {
        Ok(ClientRequest::Socks4Connect(target)) => {
            match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::Socks4)? {
                DelayConnect::Immediate => {
                    let (upstream, route) = connect_target(target.addr, state, None, false, None)?;
                    client.write_all(encode_socks4_reply(true).as_bytes())?;
                    relay(client, upstream, state, target.addr, route, None)
                }
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload);
                    let (upstream, route) =
                        connect_target_with_route(target.addr, state, route, Some(&payload), host.clone())?;
                    relay(client, upstream, state, target.addr, route, Some(payload))
                }
                DelayConnect::Closed => Ok(()),
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
    negotiate_socks5(&mut client)?;
    let request = read_socks5_request(&mut client)?;
    let session = SessionConfig { resolve: state.config.resolve, ipv6: state.config.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);

    match parse_socks5_request(&request, SocketType::Stream, session, &resolver) {
        Ok(ClientRequest::Socks5Connect(target)) => {
            match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::Socks5)? {
                DelayConnect::Immediate => match connect_target(target.addr, state, None, false, None) {
                    Ok((upstream, route)) => {
                        let reply_addr = upstream
                            .local_addr()
                            .unwrap_or_else(|_| SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0));
                        client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;
                        relay(client, upstream, state, target.addr, route, None)
                    }
                    Err(_) => {
                        let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
                        client.write_all(encode_socks5_reply(S_ER_CONN, fail).as_bytes())?;
                        Ok(())
                    }
                },
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload);
                    match connect_target_with_route(target.addr, state, route, Some(&payload), host.clone()) {
                        Ok((upstream, route)) => relay(client, upstream, state, target.addr, route, Some(payload)),
                        Err(_) => Ok(()),
                    }
                }
                DelayConnect::Closed => Ok(()),
            }
        }
        Ok(ClientRequest::Socks5UdpAssociate(_target)) => {
            if !state.config.udp {
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
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);
    match parse_http_connect_request(&request, &resolver) {
        Ok(ClientRequest::HttpConnect(target)) => {
            match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::HttpConnect)? {
                DelayConnect::Immediate => match connect_target(target.addr, state, None, false, None) {
                    Ok((upstream, route)) => {
                        client.write_all(encode_http_connect_reply(true).as_bytes())?;
                        relay(client, upstream, state, target.addr, route, None)
                    }
                    Err(_) => {
                        client.write_all(encode_http_connect_reply(false).as_bytes())?;
                        Ok(())
                    }
                },
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload);
                    match connect_target_with_route(target.addr, state, route, Some(&payload), host.clone()) {
                        Ok((upstream, route)) => relay(client, upstream, state, target.addr, route, Some(payload)),
                        Err(_) => Ok(()),
                    }
                }
                DelayConnect::Closed => Ok(()),
            }
        }
        _ => {
            client.write_all(encode_http_connect_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_shadowsocks(mut client: TcpStream, state: &RuntimeState, first_byte: u8) -> io::Result<()> {
    let (target, first_request) = read_shadowsocks_request(&mut client, first_byte, &state.config)?;
    let host = extract_host(&state.config, &first_request);
    let payload = if first_request.is_empty() { None } else { Some(first_request.as_slice()) };
    let (upstream, route) = connect_target(target, state, payload, false, host)?;
    relay(client, upstream, state, target, route, if first_request.is_empty() { None } else { Some(first_request) })
}

fn handle_socks5_udp_associate(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let relay = build_udp_relay_socket(client.local_addr()?.ip(), state.config.protect_path.as_deref())?;
    let reply_addr = relay.local_addr()?;
    client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;

    let running = Arc::new(AtomicBool::new(true));
    let worker_socket = relay.try_clone()?;
    let worker_running = running.clone();
    let worker_state = state.clone();
    let worker = thread::Builder::new()
        .name("ripdpi-udp".into())
        .spawn(move || udp_associate_loop(worker_socket, worker_state, worker_running))
        .expect("failed to spawn UDP relay thread");

    client.set_read_timeout(Some(Duration::from_millis(250)))?;
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

fn negotiate_socks5(client: &mut TcpStream) -> io::Result<()> {
    let mut count = [0u8; 1];
    client.read_exact(&mut count)?;
    let mut methods = vec![0u8; count[0] as usize];
    client.read_exact(&mut methods)?;
    let method = if methods.contains(&S_AUTH_NONE) { S_AUTH_NONE } else { S_AUTH_BAD };
    client.write_all(&[S_VER5, method])?;
    if method == S_AUTH_BAD {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks auth method"));
    }
    Ok(())
}

fn read_socks5_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => {}
    }
    Ok(out)
}

fn read_socks4_request(client: &mut TcpStream, version: u8) -> io::Result<Vec<u8>> {
    let mut out = vec![version];
    let mut fixed = [0u8; 7];
    client.read_exact(&mut fixed)?;
    out.extend_from_slice(&fixed);

    read_until_nul(client, &mut out)?;
    let is_domain = out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] != 0;
    if is_domain {
        read_until_nul(client, &mut out)?;
    }
    Ok(out)
}

fn read_until_nul(client: &mut TcpStream, out: &mut Vec<u8>) -> io::Result<()> {
    loop {
        let mut byte = [0u8; 1];
        client.read_exact(&mut byte)?;
        out.push(byte[0]);
        if byte[0] == 0 {
            return Ok(());
        }
        if out.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
}

fn read_http_connect_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during http connect request"));
        }
        out.extend_from_slice(&chunk[..n]);
        if out.windows(4).any(|window| window == b"\r\n\r\n") {
            return Ok(out);
        }
        if out.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
        }
    }
}

fn resolve_name(host: &str, _socket_type: SocketType, config: &RuntimeConfig) -> Option<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, 0));
    }
    if !config.resolve {
        return None;
    }
    (host, 0).to_socket_addrs().ok()?.find(|addr| config.ipv6 || addr.is_ipv4())
}

#[derive(Clone, Copy)]
enum HandshakeKind {
    Socks4,
    Socks5,
    HttpConnect,
}

enum DelayConnect {
    Immediate,
    Delayed { route: ConnectionRoute, payload: Vec<u8> },
    Closed,
}

fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    handshake: HandshakeKind,
) -> io::Result<DelayConnect> {
    if !state.config.delay_conn {
        return Ok(DelayConnect::Immediate);
    }
    let route = select_route(state, target, None, None, true)?;
    let group = state
        .config
        .groups
        .get(route.group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    if !group_requires_payload(group) {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake)?;
    let Some(payload) = read_blocking_first_request(client, state.config.buffer_size)? else {
        return Ok(DelayConnect::Closed);
    };

    let route = if route_matches_payload(&state.config, route.group_index, target, &payload, TransportProtocol::Tcp) {
        route
    } else {
        let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
        let host = extract_host(&state.config, &payload);
        select_next_group(
            &state.config,
            &cache,
            &route,
            target,
            Some(&payload),
            host.as_deref(),
            TransportProtocol::Tcp,
            0,
            true,
        )
            .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))?
    };

    Ok(DelayConnect::Delayed { route, payload })
}

fn send_success_reply(client: &mut TcpStream, handshake: HandshakeKind) -> io::Result<()> {
    match handshake {
        HandshakeKind::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        HandshakeKind::Socks5 => {
            let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(0, addr).as_bytes())
        }
        HandshakeKind::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(None)?;
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

fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    config: &RuntimeConfig,
) -> io::Result<(SocketAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = parse_shadowsocks_target(&request, config) {
            return Ok((target, request[header_len..].to_vec()));
        }
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
        if request.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "shadowsocks request too large"));
        }
    }
}

fn parse_shadowsocks_target(packet: &[u8], config: &RuntimeConfig) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 7 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
            let port = u16::from_be_bytes([packet[5], packet[6]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
        }
        S_ATP_I6 => {
            if packet.len() < 19 || !config.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[1..17]);
            let port = u16::from_be_bytes([packet[17], packet[18]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
        }
        0x03 => {
            let len = *packet.get(1)? as usize;
            if packet.len() < 2 + len + 2 || !config.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
            let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
            let resolved = resolve_name(host, SocketType::Stream, config)?;
            Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
        }
        _ => None,
    }
}

fn select_route(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
) -> io::Result<ConnectionRoute> {
    select_route_for_transport(state, target, payload, host, allow_unknown_payload, TransportProtocol::Tcp)
}

fn select_route_for_transport(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> io::Result<ConnectionRoute> {
    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    select_initial_group(&state.config, &mut cache, target, payload, host, allow_unknown_payload, transport)
        .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))
}

fn connect_target(
    target: SocketAddr,
    state: &RuntimeState,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let route = select_route(state, target, payload, host.as_deref(), allow_unknown_payload)?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
    }
    connect_target_with_route(target, state, route, payload, host)
}

fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    loop {
        match connect_target_via_group(target, state, route.group_index) {
            Ok(stream) => return Ok((stream, route)),
            Err(err) => {
                let next = {
                    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
                    let next = cache.advance_route(
                        &state.config,
                        &route,
                        RouteAdvance {
                            dest: target,
                            payload,
                            transport: TransportProtocol::Tcp,
                            trigger: DETECT_CONNECT,
                            can_reconnect: true,
                            host: host.clone(),
                        },
                    )?;
                    flush_autolearn_updates(state, &mut cache);
                    next
                };
                let Some(next) = next else {
                    return Err(err);
                };
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_route_advanced(
                        target,
                        route.group_index,
                        next.group_index,
                        DETECT_CONNECT,
                        host.as_deref(),
                    );
                }
                route = next;
            }
        }
    }
}

fn flush_autolearn_updates(state: &RuntimeState, cache: &mut RuntimeCache) {
    let Some(telemetry) = &state.telemetry else {
        let _ = cache.drain_autolearn_events();
        return;
    };
    let (enabled, learned_host_count, penalized_host_count) = cache.autolearn_state(&state.config);
    telemetry.on_host_autolearn_state(enabled, learned_host_count, penalized_host_count);
    for event in cache.drain_autolearn_events() {
        telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
    }
}

fn note_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
) -> io::Result<()> {
    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    cache.note_route_success(&state.config, target, route, host)?;
    flush_autolearn_updates(state, &mut cache);
    Ok(())
}

fn connect_target_via_group(target: SocketAddr, state: &RuntimeState, group_index: usize) -> io::Result<TcpStream> {
    let group = state
        .config
        .groups
        .get(group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let stream = if let Some(upstream) = group.ext_socks {
        connect_via_socks(
            target,
            upstream.addr,
            state.config.listen.bind_ip,
            state.config.protect_path.as_deref(),
            state.config.tfo,
        )
    } else {
        connect_socket(target, state.config.listen.bind_ip, state.config.protect_path.as_deref(), state.config.tfo)
    }?;

    if group.drop_sack {
        platform::attach_drop_sack(&stream)?;
    }
    Ok(stream)
}

fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
) -> io::Result<TcpStream> {
    let mut stream = connect_socket(upstream, bind_ip, protect_path, tfo)?;
    stream.write_all(&[S_VER5, 1, S_AUTH_NONE])?;
    let mut auth = [0u8; 2];
    stream.read_exact(&mut auth)?;
    if auth != [S_VER5, S_AUTH_NONE] {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream socks auth failed"));
    }

    let request = encode_upstream_socks_connect(target);
    stream.write_all(&request)?;
    let reply = read_upstream_socks_reply(&mut stream)?;
    if reply.get(1).copied().unwrap_or(S_ER_GEN) != 0 {
        return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks connect failed"));
    }
    Ok(stream)
}

fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

fn read_upstream_socks_reply(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

fn build_udp_relay_socket(ip: IpAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let bind_addr = SocketAddr::new(ip, 0);
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(socket)
}

fn udp_associate_loop(relay: UdpSocket, state: RuntimeState, running: Arc<AtomicBool>) -> io::Result<()> {
    let mut udp_client_addr = None;
    let mut buffer = [0u8; 65_535];
    let mut flow_state = HashMap::<(SocketAddr, SocketAddr), UdpFlowActivationState>::new();

    while running.load(Ordering::Relaxed) {
        match relay.recv_from(&mut buffer) {
            Ok((n, sender)) => {
                let now = Instant::now();
                flow_state.retain(|_, value| now.duration_since(value.last_used) < UDP_FLOW_IDLE_TIMEOUT);
                let known_client = udp_client_addr;
                if known_client.is_none() || known_client == Some(sender) {
                    udp_client_addr = Some(sender);
                    let Some((target, payload)) = parse_socks5_udp_packet(&buffer[..n], &state.config) else {
                        continue;
                    };
                    let host_info = extract_host_info(&state.config, payload);
                    let host = host_info.as_ref().map(|value| value.host.clone());
                    let route = match select_route_for_transport(
                        &state,
                        target,
                        Some(payload),
                        host.as_deref(),
                        false,
                        TransportProtocol::Udp,
                    ) {
                        Ok(route) => route,
                        Err(_) => continue,
                    };
                    if let Some(telemetry) = &state.telemetry {
                        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
                    }
                    if let Some(host) =
                        host.clone().filter(|_| should_cache_udp_host(&state.config, host_info.as_ref()))
                    {
                        if let Ok(mut cache) = state.cache.lock() {
                            let _ =
                                cache.store(&state.config, target, route.group_index, route.attempted_mask, Some(host));
                        }
                    }
                    let Some(group) = state.config.groups.get(route.group_index) else {
                        continue;
                    };
                    let activation = {
                        let entry = flow_state
                            .entry((sender, target))
                            .or_insert_with(|| UdpFlowActivationState { session: SessionState::default(), last_used: now });
                        entry.last_used = now;
                        let progress = entry.session.observe_datagram_outbound(payload);
                        activation_context_from_progress(progress, ActivationTransport::Udp)
                    };
                    let actions = plan_udp(group, payload, state.config.default_ttl, activation);
                    execute_udp_actions(&relay, target, &actions)?;
                } else if let Some(client_addr) = udp_client_addr {
                    let packet = encode_socks5_udp_packet(sender, &buffer[..n]);
                    relay.send_to(&packet, client_addr)?;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) => return Err(err),
        }
    }

    Ok(())
}

fn parse_socks5_udp_packet<'a>(packet: &'a [u8], config: &RuntimeConfig) -> Option<(SocketAddr, &'a [u8])> {
    if packet.len() < 4 || packet[2] != 0 {
        return None;
    }
    let atyp = packet[3];
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !config.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
        }
        0x03 => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !config.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let resolved = resolve_name(host, SocketType::Datagram, config)?;
            Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
        }
        _ => None,
    }
}

fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0, 0, 0];
    match sender {
        SocketAddr::V4(addr) => {
            packet.push(S_ATP_I4);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            packet.push(S_ATP_I6);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    packet
}

fn execute_udp_actions(relay: &UdpSocket, target: SocketAddr, actions: &[DesyncAction]) -> io::Result<()> {
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => {
                relay.send_to(bytes, target)?;
            }
            DesyncAction::SetTtl(ttl) => {
                set_udp_ttl(relay, target, *ttl)?;
            }
            DesyncAction::RestoreDefaultTtl => {}
            DesyncAction::WriteUrgent { .. }
            | DesyncAction::SetMd5Sig { .. }
            | DesyncAction::AttachDropSack
            | DesyncAction::DetachDropSack
            | DesyncAction::AwaitWritable => {}
        }
    }
    Ok(())
}

fn set_udp_ttl(relay: &UdpSocket, target: SocketAddr, ttl: u8) -> io::Result<()> {
    match target {
        SocketAddr::V4(_) => relay.set_ttl(ttl as u32),
        SocketAddr::V6(_) => Ok(()),
    }
}

fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&crate::runtime_policy::ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => matches!(config.quic_initial_mode, QuicInitialMode::RouteAndCache),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

fn connect_socket(target: SocketAddr, bind_ip: IpAddr, protect_path: Option<&str>, tfo: bool) -> io::Result<TcpStream> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    if tfo {
        platform::enable_tcp_fastopen_connect(&socket)?;
    }
    bind_socket(&socket, bind_ip, target)?;
    socket.connect(&SockAddr::from(target))?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_socket(socket: &Socket, bind_ip: IpAddr, target: SocketAddr) -> io::Result<()> {
    if is_unspecified(bind_ip) {
        return Ok(());
    }
    let bind_addr = match (bind_ip, target) {
        (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
        (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family")),
    };
    socket.bind(&SockAddr::from(bind_addr))
}

fn is_unspecified(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_unspecified(),
        IpAddr::V6(ip) => ip.is_unspecified(),
    }
}

fn relay(
    mut client: TcpStream,
    mut upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    mut route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<()> {
    let mut session_state = SessionState::default();
    let mut success_recorded = false;
    let mut success_host = seed_request.as_ref().and_then(|payload| extract_host(&state.config, payload));

    if seed_request.is_some() || needs_first_exchange(state)? {
        let request_timeout = client.read_timeout()?;
        let first_request = if let Some(seed) = seed_request {
            Some(seed)
        } else {
            read_optional_first_request(&mut client, request_timeout)?
        };
        if let Some(first_request) = first_request {
            let original_request = first_request;
            let host = extract_host(&state.config, &original_request);
            success_host = host.clone();

            loop {
                session_state = SessionState::default();
                let progress = session_state.observe_outbound(&original_request);
                let group = state.config.groups[route.group_index].clone();
                if let Err(err) = send_with_group(&mut upstream, &state.config, &group, &original_request, progress) {
                    if !runtime_supports_trigger(state, DETECT_TORST)? {
                        return Err(err);
                    }
                    let next = {
                        let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
                        let next = cache.advance_route(
                            &state.config,
                            &route,
                            RouteAdvance {
                                dest: target,
                                payload: Some(&original_request),
                                transport: TransportProtocol::Tcp,
                                trigger: DETECT_TORST,
                                can_reconnect: true,
                                host: host.clone(),
                            },
                        )?;
                        flush_autolearn_updates(state, &mut cache);
                        next
                    };
                    let Some(next) = next else {
                        return Err(err);
                    };
                    if let Some(telemetry) = &state.telemetry {
                        telemetry.on_route_advanced(
                            target,
                            route.group_index,
                            next.group_index,
                            DETECT_TORST,
                            host.as_deref(),
                        );
                    }
                    route = next;
                    upstream = reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?.0;
                    continue;
                }

                match read_first_response(
                    &mut upstream,
                    &state.config,
                    &original_request,
                    runtime_supports_trigger(state, DETECT_TORST)?,
                )? {
                    FirstResponse::Forward(bytes) => {
                        session_state.observe_inbound(&bytes);
                        client.write_all(&bytes)?;
                        if session_state.recv_count > 0 {
                            note_route_success(state, target, &route, host.as_deref())?;
                            success_recorded = true;
                        }
                        break;
                    }
                    FirstResponse::NoData => break,
                    FirstResponse::Trigger(trigger) => {
                        let next = {
                            let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
                            let next = cache.advance_route(
                                &state.config,
                                &route,
                                RouteAdvance {
                                    dest: target,
                                    payload: Some(&original_request),
                                    transport: TransportProtocol::Tcp,
                                    trigger: trigger_flag(trigger),
                                    can_reconnect: true,
                                    host: host.clone(),
                                },
                            )?;
                            flush_autolearn_updates(state, &mut cache);
                            next
                        };
                        let Some(next) = next else {
                            return Err(io::Error::new(
                                io::ErrorKind::ConnectionReset,
                                "auto trigger exhausted all candidate groups",
                            ));
                        };
                        if let Some(telemetry) = &state.telemetry {
                            telemetry.on_route_advanced(
                                target,
                                route.group_index,
                                next.group_index,
                                trigger_flag(trigger),
                                host.as_deref(),
                            );
                        }
                        route = next;
                        upstream =
                            reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?.0;
                    }
                }
            }
        }
    }

    let final_state = relay_streams(client, upstream, state, route.group_index, session_state)?;
    if !success_recorded && final_state.recv_count > 0 {
        note_route_success(state, target, &route, success_host.as_deref())?;
    }
    Ok(())
}

fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
) -> io::Result<SessionState> {
    client.set_read_timeout(None)?;
    client.set_write_timeout(None)?;
    upstream.set_read_timeout(None)?;
    upstream.set_write_timeout(None)?;

    let client_reader = client.try_clone()?;
    let client_writer = client.try_clone()?;
    let upstream_reader = upstream.try_clone()?;
    let upstream_writer = upstream.try_clone()?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let config = state.config.clone();
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.drop_sack;

    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || copy_inbound_half(upstream_reader, client_writer, inbound_session))
        .expect("failed to spawn inbound relay thread");
    let up = thread::Builder::new()
        .name("ripdpi-up".into())
        .spawn(move || copy_outbound_half(client_reader, upstream_writer, config, group, outbound_session))
        .expect("failed to spawn outbound relay thread");

    let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result?;
    down_result?;
    session_state
        .lock()
        .map_err(|_| io::Error::other("session mutex poisoned"))
        .map(|state| state.clone())
}

fn activation_context_from_progress(progress: OutboundProgress, transport: ActivationTransport) -> ActivationContext {
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        transport,
    }
}

fn needs_first_exchange(state: &RuntimeState) -> io::Result<bool> {
    Ok(runtime_supports_trigger(state, DETECT_HTTP_LOCAT)?
        || runtime_supports_trigger(state, DETECT_TLS_ERR)?
        || runtime_supports_trigger(state, DETECT_TORST)?
        || state.config.host_autolearn_enabled)
}

fn read_optional_first_request(
    client: &mut TcpStream,
    fallback_timeout: Option<Duration>,
) -> io::Result<Option<Vec<u8>>> {
    client.set_read_timeout(Some(Duration::from_millis(250)))?;
    let mut buffer = vec![0u8; 16_384];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => Ok(None),
        Err(err) => Err(err),
    };
    client.set_read_timeout(fallback_timeout)?;
    result
}

fn read_first_response(
    upstream: &mut TcpStream,
    config: &RuntimeConfig,
    request: &[u8],
    torst_enabled: bool,
) -> io::Result<FirstResponse> {
    let mut collected = Vec::new();
    let mut chunk = vec![0u8; config.buffer_size.max(16_384)];
    let mut tls_partial = TlsRecordTracker::new(request, config);
    let mut timeout_count = 0i32;

    loop {
        upstream.set_read_timeout(first_response_timeout(config, &tls_partial))?;
        let result = match upstream.read(&mut chunk) {
            Ok(0) => {
                if torst_enabled {
                    Ok(FirstResponse::Trigger(TriggerEvent::Torst))
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(trigger) = detect_response_trigger(request, &collected) {
                    if response_trigger_supported(config, trigger) {
                        Ok(FirstResponse::Trigger(trigger))
                    } else {
                        Ok(FirstResponse::Forward(collected))
                    }
                } else {
                    Ok(FirstResponse::Forward(collected))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if torst_enabled && tls_partial.waiting_for_tls_record() {
                    timeout_count += 1;
                    if timeout_count >= timeout_count_limit(config) {
                        Ok(FirstResponse::Trigger(TriggerEvent::Torst))
                    } else {
                        continue;
                    }
                } else if torst_enabled && config.timeout_ms != 0 {
                    Ok(FirstResponse::Trigger(TriggerEvent::Torst))
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Err(err)
                if torst_enabled
                    && matches!(
                        err.kind(),
                        io::ErrorKind::ConnectionReset
                            | io::ErrorKind::ConnectionAborted
                            | io::ErrorKind::BrokenPipe
                            | io::ErrorKind::ConnectionRefused
                            | io::ErrorKind::InvalidInput
                            | io::ErrorKind::TimedOut
                            | io::ErrorKind::HostUnreachable
                    ) =>
            {
                Ok(FirstResponse::Trigger(TriggerEvent::Torst))
            }
            Err(err) => Err(err),
        };
        let _ = upstream.set_read_timeout(None);
        return result;
    }
}

fn first_response_timeout(config: &RuntimeConfig, tls_partial: &TlsRecordTracker) -> Option<Duration> {
    if tls_partial.active() {
        Some(Duration::from_millis(config.partial_timeout_ms as u64))
    } else if config.timeout_ms != 0 {
        Some(Duration::from_millis(config.timeout_ms as u64))
    } else if config.groups.iter().any(|group| group.detect & (DETECT_HTTP_LOCAT | DETECT_TLS_ERR | DETECT_TORST) != 0)
    {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

fn timeout_count_limit(config: &RuntimeConfig) -> i32 {
    config.timeout_count_limit.max(1)
}

fn response_trigger_supported(config: &RuntimeConfig, trigger: TriggerEvent) -> bool {
    let flag = match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_ERR,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    };
    config.groups.iter().any(|group| group.detect & flag != 0)
}

#[derive(Default)]
struct TlsRecordTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

impl TlsRecordTracker {
    fn new(request: &[u8], config: &RuntimeConfig) -> Self {
        Self {
            enabled: ciadpi_packets::is_tls_client_hello(request) && config.partial_timeout_ms != 0,
            disabled: false,
            record_pos: 0,
            record_size: 0,
            header: [0; 5],
            total_bytes: 0,
            bytes_limit: config.timeout_bytes_limit.max(0) as usize,
        }
    }

    fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    fn observe(&mut self, bytes: &[u8]) {
        if !self.active() {
            return;
        }

        self.total_bytes += bytes.len();
        if self.bytes_limit != 0 && self.total_bytes > self.bytes_limit {
            self.disabled = true;
            return;
        }

        let mut pos = 0usize;
        while pos < bytes.len() {
            if self.record_pos < 5 {
                self.header[self.record_pos] = bytes[pos];
                self.record_pos += 1;
                pos += 1;
                if self.record_pos < 5 {
                    continue;
                }
                self.record_size = usize::from(u16::from_be_bytes([self.header[3], self.header[4]])) + 5;
                let rec_type = self.header[0];
                if !(0x14..=0x18).contains(&rec_type) {
                    self.disabled = true;
                    return;
                }
            }

            if self.record_pos == self.record_size {
                self.record_pos = 0;
                self.record_size = 0;
                continue;
            }

            let remaining = self.record_size.saturating_sub(self.record_pos);
            if remaining == 0 {
                self.disabled = true;
                return;
            }
            let take = remaining.min(bytes.len() - pos);
            self.record_pos += take;
            pos += take;
        }
    }
}

fn reconnect_target(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    loop {
        match connect_target_via_group(target, state, route.group_index) {
            Ok(stream) => return Ok((stream, route)),
            Err(err) => {
                let next = {
                    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
                    cache.advance_route(
                        &state.config,
                        &route,
                        RouteAdvance {
                            dest: target,
                            payload,
                            transport: TransportProtocol::Tcp,
                            trigger: DETECT_CONNECT,
                            can_reconnect: true,
                            host: host.clone(),
                        },
                    )?
                };
                let Some(next) = next else {
                    return Err(err);
                };
                route = next;
            }
        }
    }
}

fn runtime_supports_trigger(state: &RuntimeState, trigger: u32) -> io::Result<bool> {
    let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    Ok(cache.supports_trigger(trigger))
}

fn trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_ERR,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    }
}

enum FirstResponse {
    Forward(Vec<u8>),
    Trigger(TriggerEvent),
    NoData,
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        if let Ok(mut state) = session.lock() {
            state.observe_inbound(&buffer[..n]);
        }
        writer.write_all(&buffer[..n])?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    config: Arc<RuntimeConfig>,
    group: DesyncGroup,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        let payload = &buffer[..n];
        let progress = {
            let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
            state.observe_outbound(payload)
        };
        send_with_group(&mut writer, &config, &group, payload, progress)?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn send_with_group(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
) -> io::Result<()> {
    let context = activation_context_from_progress(progress, ActivationTransport::Tcp);
    if should_desync_tcp(group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(group, payload, seed, config.default_ttl, context) {
            Ok(plan) if group.effective_tcp_chain().iter().any(|step| matches!(step.kind, TcpChainStepKind::Fake)) => {
                execute_tcp_plan(writer, config, group, &plan, seed)?;
            }
            Ok(plan) => execute_tcp_actions(
                writer,
                &plan.actions,
                config.default_ttl,
                config.wait_send,
                Duration::from_millis(config.await_interval.max(1) as u64),
            )?,
            Err(_) => writer.write_all(payload)?,
        }
    } else {
        writer.write_all(payload)?;
    }
    Ok(())
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.mod_http != 0 || group.tlsminor.is_some()
}

fn execute_tcp_actions(
    writer: &mut TcpStream,
    actions: &[DesyncAction],
    default_ttl: u8,
    wait_send: bool,
    await_interval: Duration,
) -> io::Result<()> {
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => writer.write_all(bytes)?,
            DesyncAction::WriteUrgent { prefix, urgent_byte } => send_out_of_band(writer, prefix, *urgent_byte)?,
            DesyncAction::SetTtl(ttl) => set_stream_ttl(writer, *ttl)?,
            DesyncAction::RestoreDefaultTtl => {
                if default_ttl != 0 {
                    set_stream_ttl(writer, default_ttl)?;
                }
            }
            DesyncAction::SetMd5Sig { key_len } => platform::set_tcp_md5sig(writer, *key_len)?,
            DesyncAction::AttachDropSack => {}
            DesyncAction::DetachDropSack => {}
            DesyncAction::AwaitWritable => platform::wait_tcp_stage(writer, wait_send, await_interval)?,
        }
    }
    Ok(())
}

fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
) -> io::Result<()> {
    let fake = if plan.steps.iter().any(|step| matches!(step.kind, TcpChainStepKind::Fake)) {
        Some(
            build_fake_packet(group, &plan.tampered, seed)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync"))?,
        )
    } else {
        None
    };
    let send_steps = group
        .effective_tcp_chain()
        .into_iter()
        .filter(|step| !step.kind.is_tls_prelude())
        .collect::<Vec<_>>();
    if send_steps.len() < plan.steps.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "tcp plan steps exceed configured send steps"));
    }

    let mut cursor = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))?;
        let end = usize::try_from(step.end)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid tcp desync step bounds"));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];

        match step.kind {
            TcpChainStepKind::Split => {
                writer.write_all(chunk)?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Oob => {
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Disorder => {
                set_stream_ttl(writer, 1)?;
                writer.write_all(chunk)?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
                }
            }
            TcpChainStepKind::Disoob => {
                set_stream_ttl(writer, 1)?;
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
                }
            }
            TcpChainStepKind::Fake => {
                let fake = fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let span = chunk.len();
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                let fake_chunk = &fake.bytes[fake.fake_offset..fake_end];
                if fake_chunk.len() != span {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "fake packet prefix length does not match original split span",
                    ));
                }
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    fake_chunk,
                    group.ttl.unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(configured_step, &plan.tampered, start, end, seed) else {
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                };

                if start < span.host_start {
                    writer.write_all(&plan.tampered[start..span.host_start])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                let real_host = &plan.tampered[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, configured_step.fake_host_template.as_deref(), seed);
                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    group.ttl.unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if let Some(midhost) = span.midhost {
                    writer.write_all(&plan.tampered[span.host_start..midhost])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    writer.write_all(&plan.tampered[midhost..span.host_end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                } else {
                    writer.write_all(real_host)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    group.ttl.unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if span.host_end < end {
                    writer.write_all(&plan.tampered[span.host_end..end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "tls prelude step must not appear in tcp send plan",
                ));
            }
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        writer.write_all(&plan.tampered[cursor..])?;
    }
    Ok(())
}

fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

fn mio_to_std_stream(stream: mio::net::TcpStream) -> TcpStream {
    use std::os::fd::{FromRawFd, IntoRawFd};

    let fd = stream.into_raw_fd();
    // SAFETY: ownership of the file descriptor is moved out of the mio stream
    // and transferred directly into the std stream without duplication.
    unsafe { TcpStream::from_raw_fd(fd) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ciadpi_config::{OffsetExpr, TcpChainStep, TcpChainStepKind};
    use ciadpi_packets::DEFAULT_FAKE_TLS;
    use std::io::{Read, Write};

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn test_group() -> DesyncGroup {
        DesyncGroup::new(0)
    }

    fn test_offset() -> OffsetExpr {
        OffsetExpr::absolute(0)
    }

    #[test]
    fn client_slot_guard_enforces_limit_and_releases_slot() {
        let active = Arc::new(AtomicUsize::new(0));

        let guard = ClientSlotGuard::acquire(active.clone(), 1).expect("first slot");
        assert_eq!(active.load(Ordering::Relaxed), 1);
        assert!(ClientSlotGuard::acquire(active.clone(), 1).is_none());

        drop(guard);
        assert_eq!(active.load(Ordering::Relaxed), 0);
        assert!(ClientSlotGuard::acquire(active, 1).is_some());
    }

    #[test]
    fn send_success_reply_emits_protocol_specific_payloads() {
        let cases = [
            (HandshakeKind::Socks4, encode_socks4_reply(true).as_bytes().to_vec()),
            (
                HandshakeKind::Socks5,
                encode_socks5_reply(0, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).as_bytes().to_vec(),
            ),
            (HandshakeKind::HttpConnect, encode_http_connect_reply(true).as_bytes().to_vec()),
        ];

        for (handshake, expected) in cases {
            let (mut writer, mut reader) = connected_pair();
            reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

            send_success_reply(&mut writer, handshake).expect("send success reply");

            let mut actual = vec![0u8; expected.len()];
            reader.read_exact(&mut actual).expect("read success reply");
            assert_eq!(actual, expected);
        }
    }

    #[test]
    fn read_socks5_request_reads_domain_target() {
        let (mut reader, mut writer) = connected_pair();
        let request = [
            S_VER5, S_CMD_CONN, 0, 0x03, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm', 0x01,
            0xbb,
        ];
        writer.write_all(&request).expect("write socks5 request");

        assert_eq!(read_socks5_request(&mut reader).expect("read socks5 request"), request);
    }

    #[test]
    fn parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets() {
        let config = RuntimeConfig::default();
        let ipv4_packet = [S_ATP_I4, 127, 0, 0, 1, 0x01, 0xbb];
        let (ipv4_target, ipv4_header_len) =
            parse_shadowsocks_target(&ipv4_packet, &config).expect("parse ipv4 target");
        assert_eq!(ipv4_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
        assert_eq!(ipv4_header_len, ipv4_packet.len());

        let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0x00, 0x50];
        let (domain_target, domain_header_len) =
            parse_shadowsocks_target(&domain_packet, &config).expect("parse domain target");
        assert_eq!(domain_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80));
        assert_eq!(domain_header_len, domain_packet.len());
    }

    #[test]
    fn parse_shadowsocks_target_respects_ipv6_and_resolve_flags() {
        let config = RuntimeConfig { ipv6: false, resolve: false, ..RuntimeConfig::default() };
        let ipv6_packet = [S_ATP_I6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53];
        let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 80];

        assert!(parse_shadowsocks_target(&ipv6_packet, &config).is_none());
        assert!(parse_shadowsocks_target(&domain_packet, &config).is_none());
    }

    #[test]
    fn udp_packet_round_trip_preserves_sender_and_payload() {
        let config = RuntimeConfig::default();
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 5353);
        let payload = b"dns-payload";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &config).expect("parse udp relay packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
        let mut config = RuntimeConfig::default();
        let quic =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Quic };
        let tls =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Tls };

        config.quic_initial_mode = QuicInitialMode::Route;
        assert!(!should_cache_udp_host(&config, Some(&quic)));
        assert!(should_cache_udp_host(&config, Some(&tls)));

        config.quic_initial_mode = QuicInitialMode::RouteAndCache;
        assert!(should_cache_udp_host(&config, Some(&quic)));
    }

    #[test]
    fn encode_upstream_socks_connect_encodes_ipv6_targets() {
        let target = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8080);
        let encoded = encode_upstream_socks_connect(target);

        assert_eq!(encoded[..4], [S_VER5, S_CMD_CONN, 0, S_ATP_I6]);
        assert_eq!(&encoded[4..20], &Ipv6Addr::LOCALHOST.octets());
        assert_eq!(&encoded[20..22], &8080u16.to_be_bytes());
    }

    #[test]
    fn timeout_and_trigger_helpers_follow_runtime_configuration() {
        let mut config = RuntimeConfig { partial_timeout_ms: 75, timeout_ms: 900, ..RuntimeConfig::default() };
        let tls_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &tls_tracker), Some(Duration::from_millis(75)));

        config.partial_timeout_ms = 0;
        let inactive_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(900)));

        config.timeout_ms = 0;
        config.groups[0].detect = DETECT_HTTP_LOCAT | DETECT_CONNECT;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(250)));
        assert_eq!(timeout_count_limit(&config), 1);
        assert!(response_trigger_supported(&config, TriggerEvent::Redirect));
        assert!(response_trigger_supported(&config, TriggerEvent::Connect));
        assert!(!response_trigger_supported(&config, TriggerEvent::Torst));
        assert_eq!(trigger_flag(TriggerEvent::SslErr), DETECT_TLS_ERR);
        assert_eq!(trigger_flag(TriggerEvent::Torst), DETECT_TORST);

        config.groups[0].detect = 0;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), None);
    }

    #[test]
    fn tls_record_tracker_handles_partial_records_and_limits() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };

        let mut tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x05, 0xaa]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xbb, 0xcc, 0xdd, 0xee]);
        assert!(!tracker.waiting_for_tls_record());

        let limited_config =
            RuntimeConfig { partial_timeout_ms: 50, timeout_bytes_limit: 3, ..RuntimeConfig::default() };
        let mut limited = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &limited_config);
        limited.observe(&[0x16, 0x03, 0x03, 0x00]);
        assert!(!limited.active());

        let mut invalid = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        invalid.observe(&[0x13, 0x03, 0x03, 0x00, 0x01, 0x00]);
        assert!(!invalid.active());
    }

    #[test]
    fn tcp_desync_helpers_require_actionable_groups_and_matching_rounds() {
        let mut group = test_group();
        group.set_round_activation(Some(ciadpi_config::NumericRange::new(2, 4)));
        let in_range = ActivationContext {
            round: 3,
            payload_size: 16,
            stream_start: 0,
            stream_end: 15,
            transport: ActivationTransport::Tcp,
        };
        let out_of_range = ActivationContext { round: 5, ..in_range };

        assert!(!has_tcp_actions(&group));
        assert!(!should_desync_tcp(&group, in_range));
        assert!(activation_filter_matches(group.activation_filter(), in_range));
        assert!(!activation_filter_matches(group.activation_filter(), out_of_range));

        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
        assert!(has_tcp_actions(&group));
        assert!(should_desync_tcp(&group, in_range));
        assert!(!should_desync_tcp(&group, out_of_range));
    }
}
