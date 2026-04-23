use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use rustls::{ClientConnection, StreamOwned};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::types::{DomainTarget, QuicTarget, ScanPathMode, ScanRequest};
use crate::util::{probe_session_seed, stable_probe_hash, CONNECT_TIMEOUT, IO_TIMEOUT};

const ROUTE_BUCKET_PORT_BASE: u16 = 20_000;
const ROUTE_BUCKET_PORT_SPAN: u16 = 30_000;
// --- Types ---

#[derive(Clone, Debug)]
pub(crate) enum TransportConfig {
    Direct { route_experiment: Option<RouteExperimentConfig> },
    Socks5 { host: String, port: u16 },
}

#[derive(Clone, Debug)]
pub(crate) struct RouteExperimentConfig {
    pub(crate) stable_flow_attempts: usize,
    pub(crate) diversity_buckets: usize,
    pub(crate) diversity_on_failure_only: bool,
    pub(crate) session_seed: u64,
}

#[derive(Clone, Debug)]
pub(crate) struct RouteExperimentReport {
    pub(crate) selected_bucket: usize,
    pub(crate) selected_bucket_kind: String,
    pub(crate) stable_attempts_run: usize,
    pub(crate) diversity_attempts_run: usize,
    pub(crate) summary: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum TargetAddress {
    Host(String),
    Ip(IpAddr),
}

#[derive(Debug)]
pub(crate) struct TransportConnectResult {
    pub(crate) stream: TcpStream,
    pub(crate) connected_addr: Option<SocketAddr>,
    pub(crate) local_addr: Option<SocketAddr>,
    pub(crate) route_report: Option<RouteExperimentReport>,
}

#[derive(Debug)]
pub(crate) struct UdpRelayResult {
    pub(crate) payload: Vec<u8>,
    pub(crate) connected_addr: Option<SocketAddr>,
    pub(crate) local_addr: Option<SocketAddr>,
    pub(crate) route_report: Option<RouteExperimentReport>,
}

#[derive(Debug)]
pub(crate) enum ConnectionStream {
    Plain(TcpStream),
    Tls(Box<StreamOwned<ClientConnection, TcpStream>>),
}

impl Read for ConnectionStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.read(buf),
            Self::Tls(stream) => stream.read(buf),
        }
    }
}

impl Write for ConnectionStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.write(buf),
            Self::Tls(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(stream) => stream.flush(),
            Self::Tls(stream) => stream.flush(),
        }
    }
}

impl ConnectionStream {
    pub(crate) fn shutdown(&mut self) {
        match self {
            Self::Plain(stream) => {
                let _ = stream.shutdown(Shutdown::Both);
            }
            Self::Tls(stream) => {
                stream.conn.send_close_notify();
                let _ = stream.flush();
                let _ = stream.sock.shutdown(Shutdown::Both);
            }
        }
    }
}

// --- Transport selection ---

pub(crate) fn direct_transport() -> TransportConfig {
    TransportConfig::Direct { route_experiment: None }
}

pub(crate) fn transport_for_request_with_session(request: &ScanRequest, session_id: &str) -> TransportConfig {
    match (&request.path_mode, request.proxy_host.as_ref(), request.proxy_port) {
        (ScanPathMode::InPath, Some(host), Some(port)) => TransportConfig::Socks5 { host: host.clone(), port },
        _ => TransportConfig::Direct {
            route_experiment: request.route_probe.as_ref().map(|config| RouteExperimentConfig {
                stable_flow_attempts: config.stable_flow_attempts.max(1),
                diversity_buckets: config.diversity_buckets.max(1),
                diversity_on_failure_only: config.diversity_on_failure_only,
                session_seed: probe_session_seed(None, session_id),
            }),
        },
    }
}

pub(crate) fn describe_transport(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct { route_experiment: Some(config) } => {
            format!("DIRECT(routeProbe stable={} buckets={})", config.stable_flow_attempts, config.diversity_buckets,)
        }
        TransportConfig::Direct { route_experiment: None } => "DIRECT".to_string(),
        TransportConfig::Socks5 { host, port } => format!("SOCKS5({host}:{port})"),
    }
}

// --- Address resolution ---

pub(crate) fn domain_connect_target(target: &DomainTarget) -> TargetAddress {
    domain_connect_targets(target).into_iter().next().unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

pub(crate) fn quic_connect_target(target: &QuicTarget) -> TargetAddress {
    quic_connect_targets(target).into_iter().next().unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

pub(crate) fn domain_connect_targets(target: &DomainTarget) -> Vec<TargetAddress> {
    ordered_connect_targets(Some(target.host.as_str()), target.connect_ip.as_deref(), &target.connect_ips)
}

pub(crate) fn quic_connect_targets(target: &QuicTarget) -> Vec<TargetAddress> {
    ordered_connect_targets(Some(target.host.as_str()), target.connect_ip.as_deref(), &target.connect_ips)
}

pub(crate) fn throughput_connect_targets(
    host: Option<&str>,
    connect_ip: Option<&str>,
    connect_ips: &[String],
) -> Vec<TargetAddress> {
    ordered_connect_targets(host, connect_ip, connect_ips)
}

fn ordered_connect_targets(host: Option<&str>, connect_ip: Option<&str>, connect_ips: &[String]) -> Vec<TargetAddress> {
    let mut ordered = Vec::new();
    for value in connect_ip.into_iter().chain(connect_ips.iter().map(String::as_str)) {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Ok(ip) = trimmed.parse::<IpAddr>() {
            let target = TargetAddress::Ip(ip);
            if !ordered.contains(&target) {
                ordered.push(target);
            }
        }
    }
    if let Some(host) = host.filter(|value| !value.trim().is_empty()) {
        let fallback = TargetAddress::Host(host.to_string());
        if !ordered.contains(&fallback) {
            ordered.push(fallback);
        }
    }
    ordered
}

/// DNS resolution timeout — caps blocking `getaddrinfo` calls so that poisoned
/// or unresponsive system resolvers cannot stall probes indefinitely.
const DNS_RESOLVE_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, String> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => {
            let host = host.clone();
            let (tx, rx) = mpsc::channel();
            thread::spawn(move || {
                let _ = tx.send(
                    (host.as_str(), port).to_socket_addrs().map(Iterator::collect).map_err(|err| err.to_string()),
                );
            });
            rx.recv_timeout(DNS_RESOLVE_TIMEOUT).map_err(|_| "dns_resolve_timeout".to_string())?
        }
    }
}

pub(crate) fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, String> {
    value.to_socket_addrs().map_err(|err| err.to_string())?.next().ok_or_else(|| "no_socket_addrs".to_string())
}

// --- TCP connection ---

pub(crate) fn connect_transport_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
) -> Result<TransportConnectResult, String> {
    match transport {
        TransportConfig::Direct { route_experiment } => {
            connect_direct_observed(targets, port, route_experiment.as_ref())
        }
        TransportConfig::Socks5 { host, port: proxy_port } => {
            connect_via_socks5_observed(targets, port, host, *proxy_port)
        }
    }
}

pub(crate) fn connect_direct(target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    Ok(connect_direct_observed(std::slice::from_ref(target), port, None)?.stream)
}

fn connect_direct_observed(
    targets: &[TargetAddress],
    port: u16,
    route_experiment: Option<&RouteExperimentConfig>,
) -> Result<TransportConnectResult, String> {
    let addresses = resolve_candidate_addresses(targets, port)?;
    if let Some(config) = route_experiment {
        let route_identity = route_identity(&addresses);
        let ((stream, connected_addr, local_addr), route_report) =
            connect_addresses_with_route_experiment(&addresses, config, &route_identity)?;
        return Ok(TransportConnectResult {
            stream,
            connected_addr: Some(connected_addr),
            local_addr: Some(local_addr),
            route_report: Some(route_report),
        });
    }
    let (stream, connected_addr) = connect_addresses_with_race(&addresses)?;
    let local_addr = stream.local_addr().ok();
    Ok(TransportConnectResult { stream, connected_addr: Some(connected_addr), local_addr, route_report: None })
}

fn connect_via_socks5_observed(
    targets: &[TargetAddress],
    port: u16,
    proxy_host: &str,
    proxy_port: u16,
) -> Result<TransportConnectResult, String> {
    let mut last_error = None;
    for target in targets {
        let proxy = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
        match negotiate_socks5(proxy, target, port) {
            Ok(stream) => {
                let connected_addr = match target {
                    TargetAddress::Ip(ip) => Some(SocketAddr::new(*ip, port)),
                    TargetAddress::Host(_) => None,
                };
                let local_addr = stream.local_addr().ok();
                return Ok(TransportConnectResult { stream, connected_addr, local_addr, route_report: None });
            }
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_target_candidates".to_string()))
}

fn resolve_candidate_addresses(targets: &[TargetAddress], port: u16) -> Result<Vec<SocketAddr>, String> {
    let mut resolved = Vec::new();
    for target in targets {
        for address in resolve_addresses(target, port)? {
            if !resolved.contains(&address) {
                resolved.push(address);
            }
        }
    }
    if resolved.is_empty() {
        return Err("no_socket_addrs".to_string());
    }
    Ok(resolved)
}

fn connect_addresses_with_race(addresses: &[SocketAddr]) -> Result<(TcpStream, SocketAddr), String> {
    let initial_batch = addresses.iter().take(2).copied().collect::<Vec<_>>();
    let mut last_error = None;
    if !initial_batch.is_empty() {
        let raced = thread::scope(|scope| {
            let handles = initial_batch
                .iter()
                .map(|address| scope.spawn(move || (*address, TcpStream::connect_timeout(address, CONNECT_TIMEOUT))))
                .collect::<Vec<_>>();
            let mut winner = None;
            let mut local_last_error = None;
            for handle in handles {
                let (address, result) = handle.join().map_err(|_| "connect_race_panicked".to_string())?;
                match result {
                    Ok(stream) if winner.is_none() => winner = Some((stream, address)),
                    Ok(_) => {}
                    Err(err) => local_last_error = Some(err.to_string()),
                }
            }
            Ok::<_, String>((winner, local_last_error))
        })?;
        if let Some((stream, address)) = raced.0 {
            return Ok((stream, address));
        }
        last_error = raced.1;
    }
    for address in addresses.iter().skip(initial_batch.len()).copied() {
        match TcpStream::connect_timeout(&address, CONNECT_TIMEOUT) {
            Ok(stream) => return Ok((stream, address)),
            Err(err) => last_error = Some(err.to_string()),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

fn connect_addresses_with_route_experiment(
    addresses: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
) -> Result<((TcpStream, SocketAddr, SocketAddr), RouteExperimentReport), String> {
    let stable_attempts = config.stable_flow_attempts.max(1);
    let mut stable_attempts_run = 0usize;
    let mut diversity_attempts_run = 0usize;
    let mut events = Vec::new();
    let mut last_error = None;

    for attempt_index in 0..stable_attempts {
        stable_attempts_run += 1;
        match connect_addresses_with_bucket(addresses, config, route_identity, 0) {
            Ok(result) => {
                events.push(format!("stable#{attempt_index}:ok"));
                return Ok((
                    result,
                    RouteExperimentReport {
                        selected_bucket: 0,
                        selected_bucket_kind: "stable".to_string(),
                        stable_attempts_run,
                        diversity_attempts_run,
                        summary: events.join("|"),
                    },
                ));
            }
            Err(err) => {
                events.push(format!("stable#{attempt_index}:{err}"));
                last_error = Some(err);
            }
        }
    }

    if config.diversity_on_failure_only {
        let diversity_range = 1..config.diversity_buckets.max(1);
        for bucket in diversity_range {
            diversity_attempts_run += 1;
            match connect_addresses_with_bucket(addresses, config, route_identity, bucket) {
                Ok(result) => {
                    events.push(format!("bucket#{bucket}:ok"));
                    return Ok((
                        result,
                        RouteExperimentReport {
                            selected_bucket: bucket,
                            selected_bucket_kind: "diversity".to_string(),
                            stable_attempts_run,
                            diversity_attempts_run,
                            summary: events.join("|"),
                        },
                    ));
                }
                Err(err) => {
                    events.push(format!("bucket#{bucket}:{err}"));
                    last_error = Some(err);
                }
            }
        }
    }

    Err(format!("{}; route={}", last_error.unwrap_or_else(|| "no_addresses".to_string()), events.join("|"),))
}

fn connect_addresses_with_bucket(
    addresses: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(TcpStream, SocketAddr, SocketAddr), String> {
    let mut last_error = None;
    for address in addresses.iter().copied() {
        match connect_bound_tcp(address, config, route_identity, bucket) {
            Ok(result) => return Ok(result),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

fn connect_bound_tcp(
    address: SocketAddr,
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(TcpStream, SocketAddr, SocketAddr), String> {
    let domain = socket_domain_for(address);
    let seed = stable_probe_hash(config.session_seed, route_identity);
    let port = route_bucket_port(seed, bucket);
    let remote = SockAddr::from(address);
    let bind_addr = route_bind_addr(address, port);
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(|err| err.to_string())?;
    let _ = socket.set_reuse_address(true);
    socket.bind(&SockAddr::from(bind_addr)).map_err(|err| err.to_string())?;
    socket.connect_timeout(&remote, CONNECT_TIMEOUT).map_err(|err| err.to_string())?;
    let stream: TcpStream = socket.into();
    let local_addr = stream.local_addr().map_err(|err| err.to_string())?;
    Ok((stream, address, local_addr))
}

fn route_identity(addresses: &[SocketAddr]) -> String {
    addresses.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|")
}

fn socket_domain_for(address: SocketAddr) -> Domain {
    if address.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    }
}

fn route_bucket_port(seed: u64, bucket: usize) -> u16 {
    let bucket_seed = stable_probe_hash(seed, &format!("bucket:{bucket}"));
    ROUTE_BUCKET_PORT_BASE + (bucket_seed % u64::from(ROUTE_BUCKET_PORT_SPAN)) as u16
}

fn route_bind_addr(address: SocketAddr, port: u16) -> SocketAddr {
    if address.is_ipv4() {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port)
    } else {
        SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED), port)
    }
}

pub(crate) fn wait_for_listener(addr: SocketAddr) -> Result<(), String> {
    for _ in 0..40 {
        if TcpStream::connect_timeout(&addr, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err(format!("probe runtime listener did not become ready on {addr}"))
}

// --- SOCKS5 TCP CONNECT ---

pub(crate) fn negotiate_socks5(mut proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    proxy.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut auth_reply = [0u8; 2];
    proxy.read_exact(&mut auth_reply).map_err(|err| err.to_string())?;
    if auth_reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {auth_reply:?}"));
    }

    let mut request = vec![0x05, 0x01, 0x00];
    match target {
        TargetAddress::Ip(IpAddr::V4(ip)) => {
            request.push(0x01);
            request.extend(ip.octets());
        }
        TargetAddress::Ip(IpAddr::V6(ip)) => {
            request.push(0x04);
            request.extend(ip.octets());
        }
        TargetAddress::Host(host) => {
            let host_bytes = host.as_bytes();
            if host_bytes.len() > u8::MAX as usize {
                return Err("SOCKS5 host too long".to_string());
            }
            request.push(0x03);
            request.push(host_bytes.len() as u8);
            request.extend(host_bytes);
        }
    }
    request.extend(port.to_be_bytes());
    proxy.write_all(&request).map_err(|err| err.to_string())?;

    let mut reply = [0u8; 4];
    proxy.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply[1] != 0x00 {
        return Err(format!("SOCKS5 connect failed: {:x}", reply[1]));
    }
    match reply[3] {
        0x01 => {
            let mut tail = [0u8; 6];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x04 => {
            let mut tail = [0u8; 18];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            proxy.read_exact(&mut len).map_err(|err| err.to_string())?;
            let mut tail = vec![0u8; len[0] as usize + 2];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        atyp => return Err(format!("SOCKS5 atyp unsupported: {atyp}")),
    }
    Ok(proxy)
}

// --- SOCKS5 UDP ---

pub(crate) fn socks5_noauth_handshake(stream: &mut TcpStream) -> Result<(), String> {
    stream.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {reply:?}"));
    }
    Ok(())
}

pub(crate) fn socks5_udp_associate(stream: &mut TcpStream) -> Result<SocketAddr, String> {
    let request = [0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    stream.write_all(&request).map_err(|err| err.to_string())?;
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).map_err(|err| err.to_string())?;
    if header[1] != 0x00 {
        return Err(format!("SOCKS5 UDP ASSOCIATE failed: {:x}", header[1]));
    }
    match header[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
        }
        atyp => Err(format!("SOCKS5 UDP ASSOCIATE atyp unsupported: {atyp}")),
    }
}

pub(crate) fn normalize_udp_relay_addr(relay_addr: SocketAddr, control: &TcpStream) -> Result<SocketAddr, String> {
    if relay_addr.ip().is_unspecified() {
        let peer = control.peer_addr().map_err(|err| err.to_string())?;
        Ok(SocketAddr::new(peer.ip(), relay_addr.port()))
    } else {
        Ok(relay_addr)
    }
}

pub(crate) fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match destination {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

pub(crate) fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), String> {
    if frame.len() < 10 {
        return Err("SOCKS5 UDP frame too short".to_string());
    }
    match frame[3] {
        0x01 => {
            let address = SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7])),
                u16::from_be_bytes([frame[8], frame[9]]),
            );
            Ok((address, frame[10..].to_vec()))
        }
        0x04 => {
            if frame.len() < 22 {
                return Err("SOCKS5 UDP IPv6 frame too short".to_string());
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let address =
                SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(raw)), u16::from_be_bytes([frame[20], frame[21]]));
            Ok((address, frame[22..].to_vec()))
        }
        atyp => Err(format!("SOCKS5 UDP atyp unsupported: {atyp}")),
    }
}

// --- UDP relay ---

pub(crate) fn relay_udp_payload_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<UdpRelayResult, String> {
    match transport {
        TransportConfig::Direct { route_experiment } => {
            let destinations = resolve_candidate_addresses(targets, port)?;
            if let Some(config) = route_experiment.as_ref() {
                let route_identity = route_identity(&destinations);
                return relay_udp_direct_with_route_experiment(&destinations, payload, config, &route_identity);
            }
            let mut last_error = None;
            for destination in destinations {
                match relay_udp_direct(destination, payload) {
                    Ok((bytes, local_addr)) => {
                        return Ok(UdpRelayResult {
                            payload: bytes,
                            connected_addr: Some(destination),
                            local_addr: Some(local_addr),
                            route_report: None,
                        });
                    }
                    Err(err) => last_error = Some(err),
                }
            }
            Err(last_error.unwrap_or_else(|| "no_socket_addrs".to_string()))
        }
        TransportConfig::Socks5 { host, port: proxy_port } => {
            let mut last_error = None;
            for target in targets {
                let destination = match target {
                    TargetAddress::Ip(ip) => SocketAddr::new(*ip, port),
                    TargetAddress::Host(host_name) => {
                        let Some(address) =
                            resolve_addresses(&TargetAddress::Host(host_name.clone()), port)?.into_iter().next()
                        else {
                            continue;
                        };
                        address
                    }
                };
                match relay_udp_via_socks5(host, *proxy_port, destination, payload) {
                    Ok((bytes, local_addr)) => {
                        return Ok(UdpRelayResult {
                            payload: bytes,
                            connected_addr: Some(destination),
                            local_addr: Some(local_addr),
                            route_report: None,
                        });
                    }
                    Err(err) => last_error = Some(err),
                }
            }
            Err(last_error.unwrap_or_else(|| "no_target_candidates".to_string()))
        }
    }
}

fn relay_udp_direct_with_route_experiment(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
) -> Result<UdpRelayResult, String> {
    let stable_attempts = config.stable_flow_attempts.max(1);
    let mut stable_attempts_run = 0usize;
    let mut diversity_attempts_run = 0usize;
    let mut events = Vec::new();
    let mut last_error = None;

    for attempt_index in 0..stable_attempts {
        stable_attempts_run += 1;
        match relay_udp_bucket(destinations, payload, config, route_identity, 0) {
            Ok((bytes, destination, local_addr)) => {
                events.push(format!("stable#{attempt_index}:ok"));
                return Ok(UdpRelayResult {
                    payload: bytes,
                    connected_addr: Some(destination),
                    local_addr: Some(local_addr),
                    route_report: Some(RouteExperimentReport {
                        selected_bucket: 0,
                        selected_bucket_kind: "stable".to_string(),
                        stable_attempts_run,
                        diversity_attempts_run,
                        summary: events.join("|"),
                    }),
                });
            }
            Err(err) => {
                events.push(format!("stable#{attempt_index}:{err}"));
                last_error = Some(err);
            }
        }
    }

    if config.diversity_on_failure_only {
        for bucket in 1..config.diversity_buckets.max(1) {
            diversity_attempts_run += 1;
            match relay_udp_bucket(destinations, payload, config, route_identity, bucket) {
                Ok((bytes, destination, local_addr)) => {
                    events.push(format!("bucket#{bucket}:ok"));
                    return Ok(UdpRelayResult {
                        payload: bytes,
                        connected_addr: Some(destination),
                        local_addr: Some(local_addr),
                        route_report: Some(RouteExperimentReport {
                            selected_bucket: bucket,
                            selected_bucket_kind: "diversity".to_string(),
                            stable_attempts_run,
                            diversity_attempts_run,
                            summary: events.join("|"),
                        }),
                    });
                }
                Err(err) => {
                    events.push(format!("bucket#{bucket}:{err}"));
                    last_error = Some(err);
                }
            }
        }
    }

    Err(format!("{}; route={}", last_error.unwrap_or_else(|| "no_socket_addrs".to_string()), events.join("|"),))
}

fn relay_udp_bucket(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), String> {
    let mut last_error = None;
    for destination in destinations.iter().copied() {
        match relay_udp_direct_with_bucket(destination, payload, config, route_identity, bucket) {
            Ok(result) => return Ok(result),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_socket_addrs".to_string()))
}

pub(crate) fn relay_udp_direct(server: SocketAddr, payload: &[u8]) -> Result<(Vec<u8>, SocketAddr), String> {
    let bind_addr: SocketAddr =
        if server.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (std::net::Ipv6Addr::UNSPECIFIED, 0).into() };
    let socket = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.send_to(payload, server).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let (size, _) = socket.recv_from(&mut buf).map_err(|err| err.to_string())?;
    let local_addr = socket.local_addr().map_err(|err| err.to_string())?;
    Ok((buf[..size].to_vec(), local_addr))
}

fn relay_udp_direct_with_bucket(
    server: SocketAddr,
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), String> {
    let domain = socket_domain_for(server);
    let kind_seed = stable_probe_hash(config.session_seed, route_identity);
    let port = route_bucket_port(kind_seed, bucket);
    let bind_addr = route_bind_addr(server, port);
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).map_err(|err| err.to_string())?;
    let _ = socket.set_reuse_address(true);
    socket.bind(&SockAddr::from(bind_addr)).map_err(|err| err.to_string())?;
    let udp: UdpSocket = socket.into();
    udp.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.send_to(payload, server).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let (size, _) = udp.recv_from(&mut buf).map_err(|err| err.to_string())?;
    let local_addr = udp.local_addr().map_err(|err| err.to_string())?;
    Ok((buf[..size].to_vec(), server, local_addr))
}

pub(crate) fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
) -> Result<(Vec<u8>, SocketAddr), String> {
    let mut control = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
    control.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    control.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socks5_noauth_handshake(&mut control)?;
    let relay_addr = normalize_udp_relay_addr(socks5_udp_associate(&mut control)?, &control)?;

    let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
        "0.0.0.0:0".parse().expect("valid IPv4 UDP bind")
    } else {
        "[::]:0".parse().expect("valid IPv6 UDP bind")
    };
    let udp = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    udp.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.connect(relay_addr).map_err(|err| err.to_string())?;
    let frame = encode_socks5_udp_frame(destination, payload);
    udp.send(&frame).map_err(|err| err.to_string())?;

    let mut buf = [0u8; 65535];
    let size = udp.recv(&mut buf).map_err(|err| err.to_string())?;
    let (_, payload) = decode_socks5_udp_frame(&buf[..size])?;
    let local_addr = udp.local_addr().map_err(|err| err.to_string())?;
    Ok((payload, local_addr))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr, TcpListener};
    use std::thread;

    #[test]
    fn encode_decode_socks5_udp_frame_ipv4_roundtrip() {
        let addr: SocketAddr = "1.2.3.4:5678".parse().unwrap();
        let payload = b"hello world";
        let frame = encode_socks5_udp_frame(addr, payload);
        let (decoded_addr, decoded_payload) = decode_socks5_udp_frame(&frame).unwrap();
        assert_eq!(decoded_addr, addr);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn encode_decode_socks5_udp_frame_ipv6_roundtrip() {
        let addr = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), 443);
        let payload = b"ipv6 test";
        let frame = encode_socks5_udp_frame(addr, payload);
        let (decoded_addr, decoded_payload) = decode_socks5_udp_frame(&frame).unwrap();
        assert_eq!(decoded_addr, addr);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_short() {
        assert!(decode_socks5_udp_frame(&[0; 5]).is_err());
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_unknown_atyp() {
        let mut frame = vec![0x00, 0x00, 0x00, 0x02]; // atyp 0x02 is invalid
        frame.extend_from_slice(&[0; 10]);
        assert!(decode_socks5_udp_frame(&frame).is_err());
    }

    #[test]
    fn domain_connect_target_uses_ip_override() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: Some("1.2.3.4".to_string()),
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        };
        match domain_connect_target(&target) {
            TargetAddress::Ip(ip) => assert_eq!(ip, "1.2.3.4".parse::<IpAddr>().unwrap()),
            TargetAddress::Host(_) => panic!("expected IP"),
        }
    }

    #[test]
    fn domain_connect_target_falls_back_to_host() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        };
        match domain_connect_target(&target) {
            TargetAddress::Host(host) => assert_eq!(host, "example.com"),
            TargetAddress::Ip(_) => panic!("expected Host"),
        }
    }

    #[test]
    fn domain_connect_targets_keep_legacy_connect_ip_ahead_of_edge_list_and_host_fallback() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: Some("203.0.113.10".to_string()),
            connect_ips: vec!["203.0.113.20".to_string(), "203.0.113.10".to_string()],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        };

        let targets = domain_connect_targets(&target);

        assert_eq!(
            targets,
            vec![
                TargetAddress::Ip("203.0.113.10".parse::<IpAddr>().unwrap()),
                TargetAddress::Ip("203.0.113.20".parse::<IpAddr>().unwrap()),
                TargetAddress::Host("example.com".to_string()),
            ]
        );
    }

    #[test]
    fn resolve_addresses_with_ip_target() {
        let target = TargetAddress::Ip("127.0.0.1".parse().unwrap());
        let addrs = resolve_addresses(&target, 80).unwrap();
        assert_eq!(addrs, vec!["127.0.0.1:80".parse::<SocketAddr>().unwrap()]);
    }

    #[test]
    fn transport_for_request_direct_on_raw_path() {
        let request = ScanRequest {
            profile_id: "test".to_string(),
            display_name: "test".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: crate::types::ScanKind::Connectivity,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: vec![],
            proxy_host: Some("proxy".to_string()),
            proxy_port: Some(1080),
            probe_tasks: vec![],
            domain_targets: vec![],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            service_targets: vec![],
            circumvention_targets: vec![],
            throughput_targets: vec![],
            whitelist_sni: vec![],
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
        };
        match transport_for_request_with_session(&request, "default") {
            TransportConfig::Direct { .. } => {}
            TransportConfig::Socks5 { .. } => panic!("expected Direct for RawPath"),
        }
    }

    #[test]
    fn transport_for_request_socks5_on_in_path() {
        let request = ScanRequest {
            profile_id: "test".to_string(),
            display_name: "test".to_string(),
            path_mode: ScanPathMode::InPath,
            kind: crate::types::ScanKind::Connectivity,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: vec![],
            proxy_host: Some("proxy".to_string()),
            proxy_port: Some(1080),
            probe_tasks: vec![],
            domain_targets: vec![],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            service_targets: vec![],
            circumvention_targets: vec![],
            throughput_targets: vec![],
            whitelist_sni: vec![],
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
        };
        match transport_for_request_with_session(&request, "default") {
            TransportConfig::Socks5 { host, port } => {
                assert_eq!(host, "proxy");
                assert_eq!(port, 1080);
            }
            TransportConfig::Direct { .. } => panic!("expected Socks5 for InPath"),
        }
    }

    #[test]
    fn describe_transport_direct() {
        assert_eq!(describe_transport(&direct_transport()), "DIRECT");
    }

    #[test]
    fn describe_transport_socks5() {
        let t = TransportConfig::Socks5 { host: "1.2.3.4".to_string(), port: 1080 };
        assert_eq!(describe_transport(&t), "SOCKS5(1.2.3.4:1080)");
    }

    #[test]
    fn direct_route_experiment_binds_deterministic_stable_bucket_port() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
        let server_addr = listener.local_addr().expect("listener addr");
        let accept_handle = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let _ = stream.shutdown(Shutdown::Both);
        });
        let config = RouteExperimentConfig {
            stable_flow_attempts: 1,
            diversity_buckets: 1,
            diversity_on_failure_only: true,
            session_seed: 7,
        };
        let identity = route_identity(&[server_addr]);
        let expected_port = route_bucket_port(crate::util::stable_probe_hash(config.session_seed, &identity), 0);

        let result = connect_transport_observed(
            &[TargetAddress::Ip(server_addr.ip())],
            server_addr.port(),
            &TransportConfig::Direct { route_experiment: Some(config.clone()) },
        )
        .expect("route-stable connect");

        assert_eq!(result.local_addr.expect("local addr").port(), expected_port);
        let route_report = result.route_report.expect("route report");
        assert_eq!(route_report.selected_bucket, 0);
        assert_eq!(route_report.selected_bucket_kind, "stable");
        accept_handle.join().expect("join accept");
    }

    #[test]
    fn direct_route_experiment_uses_diversity_bucket_when_stable_port_is_busy() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
        let server_addr = listener.local_addr().expect("listener addr");
        let accept_handle = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let _ = stream.shutdown(Shutdown::Both);
        });
        let config = RouteExperimentConfig {
            stable_flow_attempts: 1,
            diversity_buckets: 2,
            diversity_on_failure_only: true,
            session_seed: 9,
        };
        let identity = route_identity(&[server_addr]);
        let stable_port = route_bucket_port(crate::util::stable_probe_hash(config.session_seed, &identity), 0);
        let occupied =
            TcpListener::bind(route_bind_addr(server_addr, stable_port)).expect("occupy stable route bucket");

        let result = connect_transport_observed(
            &[TargetAddress::Ip(server_addr.ip())],
            server_addr.port(),
            &TransportConfig::Direct { route_experiment: Some(config) },
        )
        .expect("route-diversity connect");

        let route_report = result.route_report.expect("route report");
        assert_eq!(route_report.selected_bucket, 1);
        assert_eq!(route_report.selected_bucket_kind, "diversity");
        assert!(route_report.summary.contains("stable#0"));
        assert!(route_report.summary.contains("bucket#1:ok"));
        assert_ne!(result.local_addr.expect("local addr").port(), stable_port);

        drop(occupied);
        accept_handle.join().expect("join accept");
    }
}
