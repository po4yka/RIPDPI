use std::collections::{HashMap, HashSet, VecDeque};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, ToSocketAddrs};
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Context};
use base64::decode;
use boringtun::noise::{Tunn, TunnResult};
use bytes::{Bytes, BytesMut};
use ripdpi_native_protect::protect_socket_via_callback;
use serde::{Deserialize, Serialize};
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::DeviceCapabilities;
use smoltcp::phy::Medium;
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Packet, Ipv6Packet};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpListener, TcpStream, UdpSocket};
use tokio::sync::{broadcast, RwLock};
use tokio::time::{timeout, Duration};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const READY_SOURCE: &str = "warp";
const MAX_PACKET: usize = 65_536;
const MIN_VIRTUAL_PORT: u16 = 1000;
const MAX_VIRTUAL_PORT: u16 = 60_999;

#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
enum PortProtocol {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy)]
struct PortForwardConfig {
    source: SocketAddr,
    destination: SocketAddr,
    protocol: PortProtocol,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash, Ord, PartialOrd)]
struct VirtualPort(u16, PortProtocol);

impl VirtualPort {
    fn new(port: u16, protocol: PortProtocol) -> Self {
        Self(port, protocol)
    }

    fn num(self) -> u16 {
        self.0
    }
}

#[derive(Debug, Clone)]
enum Event {
    ClientConnectionInitiated(PortForwardConfig, VirtualPort),
    ClientConnectionDropped(VirtualPort),
    LocalData(PortForwardConfig, VirtualPort, Bytes),
    RemoteData(VirtualPort, Bytes),
    InboundInternetPacket(PortProtocol, Bytes),
    OutboundInternetPacket(Bytes),
    VirtualDeviceFed(PortProtocol),
}

#[derive(Clone)]
struct Bus {
    counter: Arc<AtomicU64>,
    tx: broadcast::Sender<(u64, Event)>,
}

impl Bus {
    fn new() -> Self {
        let (tx, _) = broadcast::channel(1024);
        Self { counter: Arc::new(AtomicU64::new(0)), tx }
    }

    fn new_endpoint(&self) -> BusEndpoint {
        let id = self.counter.fetch_add(1, Ordering::Relaxed);
        BusEndpoint { id, tx: self.tx.clone(), rx: self.tx.subscribe() }
    }
}

struct BusEndpoint {
    id: u64,
    tx: broadcast::Sender<(u64, Event)>,
    rx: broadcast::Receiver<(u64, Event)>,
}

impl BusEndpoint {
    fn send(&self, event: Event) {
        let _ = self.tx.send((self.id, event));
    }

    async fn recv(&mut self) -> Event {
        loop {
            match self.rx.recv().await {
                Ok((id, event)) if id != self.id => return event,
                Ok(_) => continue,
                Err(_) => continue,
            }
        }
    }
}

struct VirtualIpDevice {
    max_transmission_unit: usize,
    bus_sender: broadcast::Sender<(u64, Event)>,
    sender_id: u64,
    process_queue: Arc<Mutex<VecDeque<Bytes>>>,
}

impl VirtualIpDevice {
    fn new(protocol: PortProtocol, bus: Bus, max_transmission_unit: usize) -> Self {
        let mut endpoint = bus.new_endpoint();
        let bus_sender = endpoint.tx.clone();
        let sender_id = endpoint.id;
        let process_queue = Arc::new(Mutex::new(VecDeque::new()));
        {
            let process_queue = Arc::clone(&process_queue);
            tokio::spawn(async move {
                loop {
                    match endpoint.recv().await {
                        Event::InboundInternetPacket(packet_protocol, data) if packet_protocol == protocol => {
                            process_queue.lock().expect("process queue").push_back(data);
                            endpoint.send(Event::VirtualDeviceFed(packet_protocol));
                        }
                        _ => {}
                    }
                }
            });
        }
        Self { max_transmission_unit, bus_sender, sender_id, process_queue }
    }
}

impl smoltcp::phy::Device for VirtualIpDevice {
    type RxToken<'a>
        = DeviceRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = DeviceTxToken
    where
        Self: 'a;

    fn receive(&mut self, _timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let next = self.process_queue.lock().expect("process queue").pop_front()?;
        Some((DeviceRxToken { buffer: next }, DeviceTxToken { tx: self.bus_sender.clone(), sender_id: self.sender_id }))
    }

    fn transmit(&mut self, _timestamp: Instant) -> Option<Self::TxToken<'_>> {
        Some(DeviceTxToken { tx: self.bus_sender.clone(), sender_id: self.sender_id })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut cap = DeviceCapabilities::default();
        cap.medium = Medium::Ip;
        cap.max_transmission_unit = self.max_transmission_unit;
        cap
    }
}

struct DeviceRxToken {
    buffer: Bytes,
}

impl smoltcp::phy::RxToken for DeviceRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.buffer)
    }
}

struct DeviceTxToken {
    tx: broadcast::Sender<(u64, Event)>,
    sender_id: u64,
}

impl smoltcp::phy::TxToken for DeviceTxToken {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        let _ = self.tx.send((self.sender_id, Event::OutboundInternetPacket(Bytes::from(buffer))));
        result
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeEndpoint {
    pub host: String,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub port: i32,
    pub source: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub account_kind: String,
    pub device_id: String,
    pub access_token: String,
    pub client_id: Option<String>,
    pub private_key: String,
    pub public_key: String,
    pub peer_public_key: String,
    pub interface_address_v4: Option<String>,
    pub interface_address_v6: Option<String>,
    pub endpoint: ResolvedWarpRuntimeEndpoint,
    pub route_mode: String,
    pub route_hosts: String,
    pub built_in_rules_enabled: bool,
    pub endpoint_selection_mode: String,
    pub manual_endpoint: WarpManualEndpoint,
    pub scanner_enabled: bool,
    pub scanner_parallelism: i32,
    pub scanner_max_rtt_ms: i32,
    pub amnezia: WarpAmneziaConfig,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub mtu: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WarpManualEndpoint {
    pub host: String,
    pub ipv4: String,
    pub ipv6: String,
    pub port: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WarpAmneziaConfig {
    pub enabled: bool,
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub h1: i64,
    pub h2: i64,
    pub h3: i64,
    pub h4: i64,
    pub s1: i32,
    pub s2: i32,
    pub s3: i32,
    pub s4: i32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub upstream_rtt_ms: Option<u64>,
    pub profile_id: Option<String>,
    pub last_error: Option<String>,
    pub captured_at: u64,
}

pub struct WarpRuntime {
    config: ResolvedWarpRuntimeConfig,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
}

impl WarpRuntime {
    pub fn new(config: ResolvedWarpRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            config,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            listener_address: Mutex::new(None),
            last_error: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> WarpTelemetry {
        WarpTelemetry {
            source: READY_SOURCE,
            state: if self.running.load(Ordering::SeqCst) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::SeqCst) { "running".to_string() } else { "idle".to_string() },
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(format!("{}:{}", self.config.endpoint.host, self.config.endpoint.port)),
            upstream_rtt_ms: None,
            profile_id: Some(self.config.profile_id.clone()),
            last_error: self.last_error.lock().expect("last error").clone(),
            captured_at: now_ms(),
        }
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        if !self.config.enabled {
            return Ok(());
        }
        let source_peer_ip = parse_ipv4_cidr(self.config.interface_address_v4.as_deref()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "WARP runtime requires IPv4 interface address")
        })?;
        let endpoint = resolve_endpoint(&self.config.endpoint).await?;
        let reserved = reserved_bytes_from_client_id(self.config.client_id.as_deref());
        // AmneziaWG packet obfuscation is configured but the runtime byte-level
        // obfuscation (header replacement, junk packets) is not yet implemented.
        // The tunnel will function as standard WireGuard without obfuscation.
        if self.config.amnezia.enabled {
            tracing::warn!(
                "AmneziaWG obfuscation parameters are configured (jc={}, h1-h4 ranges) \
                 but runtime obfuscation is not yet implemented; \
                 tunnel will use standard WireGuard framing",
                self.config.amnezia.jc
            );
        }
        let tunnel = Arc::new(
            WireGuardTunnel::new(
                &self.config.private_key,
                &self.config.peer_public_key,
                endpoint,
                reserved,
                source_peer_ip,
            )
            .await
            .map_err(to_io_error)?,
        );
        let bus = Bus::new();
        let tcp_pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp));
        let udp_pool = Arc::new(UdpAssociationPool::new());

        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tokio::spawn(async move { tunnel.consume_task(bus).await });
        }
        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tokio::spawn(async move { tunnel.produce_task(bus).await });
        }
        {
            let tunnel = Arc::clone(&tunnel);
            tokio::spawn(async move { tunnel.routine_task().await });
        }
        {
            let interface = DynamicTcpInterface::new(bus.clone(), source_peer_ip, self.config.mtu.max(1280) as usize);
            tokio::spawn(async move { interface.run().await });
        }
        {
            let interface = DynamicUdpInterface::new(bus.clone(), source_peer_ip, self.config.mtu.max(1280) as usize);
            tokio::spawn(async move { interface.run().await });
        }

        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    self.active_sessions.fetch_add(1, Ordering::SeqCst);
                    self.total_sessions.fetch_add(1, Ordering::SeqCst);
                    let runtime = Arc::clone(&self);
                    let bus = bus.clone();
                    let tcp_pool = Arc::clone(&tcp_pool);
                    let udp_pool = Arc::clone(&udp_pool);
                    tokio::spawn(async move {
                        if let Err(error) = handle_socks_client(stream, bus, tcp_pool, udp_pool).await {
                            *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        Ok(())
    }
}

async fn handle_socks_client(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    udp_pool: Arc<UdpAssociationPool>,
) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }
    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    client.read_exact(&mut methods).await?;
    client.write_all(&[0x05, 0x00]).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS request"));
    }
    let target = read_target(&mut client, request_header[3]).await?;

    match request_header[1] {
        0x01 => handle_tcp_connect(client, bus, tcp_pool, target).await,
        0x03 => handle_udp_associate(client, bus, udp_pool).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS command unsupported"))
        }
    }
}

async fn handle_tcp_connect(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    target: SocketAddr,
) -> io::Result<()> {
    let virtual_port = tcp_pool.acquire().await?;
    let port_forward = PortForwardConfig {
        source: SocketAddr::from((Ipv4Addr::LOCALHOST, 0)),
        destination: target,
        protocol: PortProtocol::Tcp,
    };
    let mut endpoint = bus.new_endpoint();
    endpoint.send(Event::ClientConnectionInitiated(port_forward, virtual_port));
    write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;

    let mut buffer = BytesMut::with_capacity(MAX_PACKET);
    loop {
        tokio::select! {
            readable_result = client.readable() => {
                match readable_result {
                    Ok(_) => match client.try_read_buf(&mut buffer) {
                        Ok(size) if size > 0 => {
                            endpoint.send(Event::LocalData(port_forward, virtual_port, Bytes::copy_from_slice(&buffer[..size])));
                            buffer.clear();
                        }
                        Ok(_) => break,
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                        Err(error) => return Err(error),
                    },
                    Err(error) => return Err(error),
                }
            }
            event = endpoint.recv() => match event {
                Event::ClientConnectionDropped(event_port) if event_port == virtual_port => break,
                Event::RemoteData(event_port, data) if event_port == virtual_port => {
                    client.write_all(&data).await?;
                }
                _ => {}
            }
        }
    }

    endpoint.send(Event::ClientConnectionDropped(virtual_port));
    tcp_pool.release(virtual_port).await;
    Ok(())
}

async fn handle_udp_associate(mut control: TcpStream, bus: Bus, udp_pool: Arc<UdpAssociationPool>) -> io::Result<()> {
    let udp_socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await?;
    let bind_addr = udp_socket.local_addr()?;
    write_reply(&mut control, 0x00, bind_addr).await?;
    let association_bind_port = bind_addr.port();
    let mut endpoint = bus.new_endpoint();
    let mut buffer = [0u8; MAX_PACKET];
    let mut known_ports = HashSet::new();
    let mut targets = HashMap::new();

    loop {
        tokio::select! {
            readable_result = control.readable() => {
                match readable_result {
                    Ok(_) => {
                        let mut one = [0u8; 1];
                        match control.try_read(&mut one) {
                            Ok(0) => break,
                            Ok(_) => continue,
                            Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                            Err(error) => return Err(error),
                        }
                    }
                    Err(error) => return Err(error),
                }
            }
            recv_result = udp_socket.recv_from(&mut buffer) => {
                let (size, peer_addr) = recv_result?;
                let (target, payload) = parse_socks_udp_request(&buffer[..size]).map_err(to_io_error)?;
                let virtual_port = udp_pool.acquire(association_bind_port, peer_addr).await?;
                known_ports.insert(virtual_port);
                targets.insert(virtual_port, target);
                let port_forward = PortForwardConfig { source: SocketAddr::from((Ipv4Addr::LOCALHOST, 0)), destination: target, protocol: PortProtocol::Udp };
                endpoint.send(Event::LocalData(port_forward, virtual_port, payload));
            }
            event = endpoint.recv() => match event {
                Event::RemoteData(virtual_port, data) if known_ports.contains(&virtual_port) => {
                    if let Some(peer_addr) = udp_pool.peer_addr(virtual_port).await {
                        let target = targets.get(&virtual_port).copied().unwrap_or_else(|| SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)));
                        let packet = encode_socks_udp_response(target, &data);
                        udp_socket.send_to(&packet, peer_addr).await?;
                    }
                }
                _ => {}
            }
        }
    }

    udp_pool.release_association(association_bind_port).await;
    Ok(())
}

struct WireGuardTunnel {
    peer: tokio::sync::Mutex<Box<Tunn>>,
    udp: UdpSocket,
    endpoint: SocketAddr,
    source_peer_ip: IpAddr,
    reserved: [u8; 3],
}

impl WireGuardTunnel {
    async fn new(
        private_key: &str,
        peer_public_key: &str,
        endpoint: SocketAddr,
        reserved: [u8; 3],
        source_peer_ip: IpAddr,
    ) -> anyhow::Result<Self> {
        let private_key = decode_key(private_key).context("invalid WARP private key")?;
        let peer_public_key = decode_key(peer_public_key).context("invalid WARP peer public key")?;
        let peer = Box::new(Tunn::new(
            boringtun::x25519::StaticSecret::from(private_key),
            boringtun::x25519::PublicKey::from(peer_public_key),
            None,
            Some(25),
            0,
            None,
        ));
        let bind_addr = if endpoint.is_ipv4() {
            SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
        } else {
            "[::]:0".parse().expect("ipv6 bind addr")
        };
        let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
        socket.bind(&bind_addr.into())?;
        let _ = protect_socket_via_callback(socket.as_raw_fd());
        socket.set_nonblocking(true)?;
        let udp = UdpSocket::from_std(socket.into())?;
        Ok(Self { peer: tokio::sync::Mutex::new(peer), udp, endpoint, source_peer_ip, reserved })
    }

    async fn send_ip_packet(&self, packet: &[u8]) {
        let mut send_buf = [0u8; MAX_PACKET];
        let result = { self.peer.lock().await.encapsulate(packet, &mut send_buf) };
        self.send_tunn_result(result).await;
    }

    async fn send_tunn_result<'a>(&self, result: TunnResult<'a>) {
        match result {
            TunnResult::WriteToNetwork(packet) => {
                let mut payload = packet.to_vec();
                apply_reserved_bytes(&mut payload, self.reserved);
                let _ = self.udp.send_to(&payload, self.endpoint).await;
            }
            TunnResult::Done => {}
            TunnResult::Err(error) => tracing::warn!("WARP tunnel write failed: {error:?}"),
            _ => {}
        }
    }

    async fn produce_task(&self, bus: Bus) -> ! {
        let mut endpoint = bus.new_endpoint();
        loop {
            if let Event::OutboundInternetPacket(packet) = endpoint.recv().await {
                self.send_ip_packet(&packet).await;
            }
        }
    }

    async fn routine_task(&self) -> ! {
        loop {
            let mut send_buf = [0u8; MAX_PACKET];
            let result = { self.peer.lock().await.update_timers(&mut send_buf) };
            self.send_tunn_result(result).await;
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    }

    async fn consume_task(&self, bus: Bus) -> ! {
        let endpoint = bus.new_endpoint();
        loop {
            let mut recv_buf = [0u8; MAX_PACKET];
            let mut send_buf = [0u8; MAX_PACKET];
            let size = match self.udp.recv(&mut recv_buf).await {
                Ok(size) => size,
                Err(error) => {
                    tracing::warn!("WARP tunnel recv failed: {error}");
                    tokio::time::sleep(Duration::from_millis(10)).await;
                    continue;
                }
            };
            let data = &recv_buf[..size];
            let result = { self.peer.lock().await.decapsulate(None, data, &mut send_buf) };
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    let mut payload = packet.to_vec();
                    apply_reserved_bytes(&mut payload, self.reserved);
                    let _ = self.udp.send_to(&payload, self.endpoint).await;
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    if let Some(protocol) = route_protocol(packet, self.source_peer_ip) {
                        endpoint.send(Event::InboundInternetPacket(protocol, Bytes::copy_from_slice(packet)));
                    }
                }
                TunnResult::Done => {}
                TunnResult::Err(error) => tracing::warn!("WARP tunnel decapsulation failed: {error:?}"),
            }
        }
    }
}

struct DynamicTcpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    mtu: usize,
}

impl DynamicTcpInterface {
    fn new(bus: Bus, source_peer_ip: IpAddr, mtu: usize) -> Self {
        Self { bus, source_peer_ip, mtu }
    }

    async fn run(self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Tcp, self.bus.clone(), self.mtu);
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, Instant::now());
        let mut ip_addrs = HashSet::new();
        ip_addrs.insert(IpAddress::from(self.source_peer_ip));
        iface.update_ip_addrs(|addrs| {
            addrs.push(IpCidr::new(IpAddress::from(self.source_peer_ip), 32)).expect("source ip");
        });
        let mut endpoint = self.bus.new_endpoint();
        let mut next_poll: Option<tokio::time::Instant> = None;
        let mut port_client_handle_map: HashMap<VirtualPort, SocketHandle> = HashMap::new();
        let mut send_queue: HashMap<VirtualPort, VecDeque<Bytes>> = HashMap::new();

        loop {
            tokio::select! {
                _ = match (next_poll, port_client_handle_map.len()) {
                    (None, 0) => tokio::time::sleep(Duration::MAX),
                    (None, _) => tokio::time::sleep(Duration::ZERO),
                    (Some(until), _) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    port_client_handle_map.retain(|virtual_port, client_handle| {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        if client_socket.state() == tcp::State::Closed {
                            endpoint.send(Event::ClientConnectionDropped(*virtual_port));
                            send_queue.remove(virtual_port);
                            sockets.remove(*client_handle);
                            false
                        } else {
                            true
                        }
                    });

                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    for (virtual_port, client_handle) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        if client_socket.can_send() {
                            if let Some(queue) = send_queue.get_mut(virtual_port) {
                                if let Some(to_send) = queue.pop_front() {
                                    let total = to_send.len();
                                    if let Ok(sent) = client_socket.send_slice(&to_send) {
                                        if sent < total {
                                            queue.push_front(Bytes::copy_from_slice(&to_send[sent..]));
                                        }
                                    }
                                } else if client_socket.state() == tcp::State::CloseWait {
                                    client_socket.close();
                                }
                            }
                        }
                        if client_socket.can_recv() {
                            if let Ok(data) = client_socket.recv(|buffer| (buffer.len(), Bytes::copy_from_slice(buffer))) {
                                if !data.is_empty() {
                                    endpoint.send(Event::RemoteData(*virtual_port, data));
                                }
                            }
                        }
                    }
                    next_poll = iface.poll_delay(loop_start, &sockets).map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                }
                event = endpoint.recv() => match event {
                    Event::ClientConnectionInitiated(port_forward, virtual_port) if matches!(port_forward.protocol, PortProtocol::Tcp) => {
                        let dest_ip = IpAddress::from(port_forward.destination.ip());
                        if ip_addrs.insert(dest_ip) {
                            iface.update_ip_addrs(|addrs| {
                                let prefix = match dest_ip.version() { IpVersion::Ipv4 => 32, IpVersion::Ipv6 => 128 };
                                let _ = addrs.push(IpCidr::new(dest_ip, prefix));
                            });
                        }
                        let client_handle = sockets.add(new_tcp_client_socket());
                        port_client_handle_map.insert(virtual_port, client_handle);
                        send_queue.insert(virtual_port, VecDeque::new());
                        let client_socket = sockets.get_mut::<tcp::Socket>(client_handle);
                        client_socket
                            .connect(
                                iface.context(),
                                (IpAddress::from(port_forward.destination.ip()), port_forward.destination.port()),
                                (IpAddress::from(self.source_peer_ip), virtual_port.num()),
                            )
                            .context("TCP virtual connect failed")?;
                        next_poll = None;
                    }
                    Event::ClientConnectionDropped(virtual_port) => {
                        if let Some(client_handle) = port_client_handle_map.remove(&virtual_port) {
                            sockets.get_mut::<tcp::Socket>(client_handle).close();
                            sockets.remove(client_handle);
                            send_queue.remove(&virtual_port);
                        }
                    }
                    Event::LocalData(_, virtual_port, data) if send_queue.contains_key(&virtual_port) => {
                        send_queue.get_mut(&virtual_port).expect("queue exists").push_back(data);
                        next_poll = None;
                    }
                    Event::VirtualDeviceFed(PortProtocol::Tcp) => next_poll = None,
                    _ => {}
                }
            }
        }
    }
}

struct DynamicUdpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    mtu: usize,
}

impl DynamicUdpInterface {
    fn new(bus: Bus, source_peer_ip: IpAddr, mtu: usize) -> Self {
        Self { bus, source_peer_ip, mtu }
    }

    async fn run(self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Udp, self.bus.clone(), self.mtu);
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, Instant::now());
        let mut ip_addrs = HashSet::new();
        ip_addrs.insert(IpAddress::from(self.source_peer_ip));
        iface.update_ip_addrs(|addrs| {
            addrs.push(IpCidr::new(IpAddress::from(self.source_peer_ip), 32)).expect("source ip");
        });
        let mut endpoint = self.bus.new_endpoint();
        let mut next_poll: Option<tokio::time::Instant> = None;
        let mut port_client_handle_map: HashMap<VirtualPort, SocketHandle> = HashMap::new();
        let mut send_queue: HashMap<VirtualPort, VecDeque<(SocketAddr, Bytes)>> = HashMap::new();

        loop {
            tokio::select! {
                _ = match (next_poll, port_client_handle_map.len()) {
                    (None, 0) => tokio::time::sleep(Duration::MAX),
                    (None, _) => tokio::time::sleep(Duration::ZERO),
                    (Some(until), _) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    for (virtual_port, client_handle) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<udp::Socket>(*client_handle);
                        if client_socket.can_send() {
                            if let Some(queue) = send_queue.get_mut(virtual_port) {
                                if let Some((target, data)) = queue.pop_front() {
                                    let _ = client_socket.send_slice(&data, udp::UdpMetadata::from(target));
                                }
                            }
                        }
                        if client_socket.can_recv() {
                            if let Ok((data, _peer)) = client_socket.recv() {
                                if !data.is_empty() {
                                    endpoint.send(Event::RemoteData(*virtual_port, Bytes::copy_from_slice(data)));
                                }
                            }
                        }
                    }
                    next_poll = iface.poll_delay(loop_start, &sockets).map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                }
                event = endpoint.recv() => match event {
                    Event::LocalData(port_forward, virtual_port, data) if matches!(port_forward.protocol, PortProtocol::Udp) => {
                        let dest_ip = IpAddress::from(port_forward.destination.ip());
                        if ip_addrs.insert(dest_ip) {
                            iface.update_ip_addrs(|addrs| {
                                let prefix = match dest_ip.version() { IpVersion::Ipv4 => 32, IpVersion::Ipv6 => 128 };
                                let _ = addrs.push(IpCidr::new(dest_ip, prefix));
                            });
                        }
                        if let std::collections::hash_map::Entry::Vacant(entry) = port_client_handle_map.entry(virtual_port) {
                            let socket_handle = sockets.add(new_udp_client_socket(self.source_peer_ip, virtual_port)?);
                            entry.insert(socket_handle);
                            send_queue.insert(virtual_port, VecDeque::new());
                        }
                        send_queue
                            .get_mut(&virtual_port)
                            .expect("udp queue exists")
                            .push_back((port_forward.destination, data));
                        next_poll = None;
                    }
                    Event::VirtualDeviceFed(PortProtocol::Udp) => next_poll = None,
                    _ => {}
                }
            }
        }
    }
}

#[derive(Clone)]
struct VirtualPortPool {
    protocol: PortProtocol,
    free_ports: Arc<RwLock<VecDeque<u16>>>,
}

impl VirtualPortPool {
    fn new(protocol: PortProtocol) -> Self {
        let mut ports = VecDeque::new();
        for port in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT {
            ports.push_back(port);
        }
        Self { protocol, free_ports: Arc::new(RwLock::new(ports)) }
    }

    async fn acquire(&self) -> io::Result<VirtualPort> {
        self.free_ports
            .write()
            .await
            .pop_front()
            .map(|port| VirtualPort::new(port, self.protocol))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "virtual port pool exhausted"))
    }

    async fn release(&self, port: VirtualPort) {
        self.free_ports.write().await.push_back(port.num());
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
struct UdpAssociationKey {
    bind_port: u16,
    peer_addr: SocketAddr,
}

#[derive(Default)]
struct UdpAssociationPoolState {
    free_ports: VecDeque<u16>,
    by_key: HashMap<UdpAssociationKey, VirtualPort>,
    by_port: HashMap<u16, UdpAssociationKey>,
}

#[derive(Clone, Default)]
struct UdpAssociationPool {
    state: Arc<RwLock<UdpAssociationPoolState>>,
}

impl UdpAssociationPool {
    fn new() -> Self {
        let mut state = UdpAssociationPoolState::default();
        for port in MIN_VIRTUAL_PORT..MAX_VIRTUAL_PORT {
            state.free_ports.push_back(port);
        }
        Self { state: Arc::new(RwLock::new(state)) }
    }

    async fn acquire(&self, bind_port: u16, peer_addr: SocketAddr) -> io::Result<VirtualPort> {
        let key = UdpAssociationKey { bind_port, peer_addr };
        let mut state = self.state.write().await;
        if let Some(port) = state.by_key.get(&key).copied() {
            return Ok(port);
        }
        let port = state
            .free_ports
            .pop_front()
            .map(|raw| VirtualPort::new(raw, PortProtocol::Udp))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "udp virtual port pool exhausted"))?;
        state.by_key.insert(key, port);
        state.by_port.insert(port.num(), key);
        Ok(port)
    }

    async fn peer_addr(&self, port: VirtualPort) -> Option<SocketAddr> {
        self.state.read().await.by_port.get(&port.num()).map(|key| key.peer_addr)
    }

    async fn release_association(&self, bind_port: u16) {
        let mut state = self.state.write().await;
        let ports: Vec<u16> =
            state.by_port.iter().filter_map(|(port, key)| (key.bind_port == bind_port).then_some(*port)).collect();
        for port in ports {
            if let Some(key) = state.by_port.remove(&port) {
                state.by_key.remove(&key);
                state.free_ports.push_back(port);
            }
        }
    }
}

fn new_tcp_client_socket() -> tcp::Socket<'static> {
    let rx_data = vec![0u8; MAX_PACKET];
    let tx_data = vec![0u8; MAX_PACKET];
    let tcp_rx_buffer = tcp::SocketBuffer::new(rx_data);
    let tcp_tx_buffer = tcp::SocketBuffer::new(tx_data);
    tcp::Socket::new(tcp_rx_buffer, tcp_tx_buffer)
}

fn new_udp_client_socket(source_peer_ip: IpAddr, virtual_port: VirtualPort) -> anyhow::Result<udp::Socket<'static>> {
    let rx_meta = vec![udp::PacketMetadata::EMPTY; 10];
    let tx_meta = vec![udp::PacketMetadata::EMPTY; 10];
    let rx_data = vec![0u8; MAX_PACKET];
    let tx_data = vec![0u8; MAX_PACKET];
    let udp_rx_buffer = udp::PacketBuffer::new(rx_meta, rx_data);
    let udp_tx_buffer = udp::PacketBuffer::new(tx_meta, tx_data);
    let mut socket = udp::Socket::new(udp_rx_buffer, udp_tx_buffer);
    socket.bind((IpAddress::from(source_peer_ip), virtual_port.num())).context("udp virtual client bind failed")?;
    Ok(socket)
}

fn resolve_sync_host(host: &str, port: u16) -> io::Result<SocketAddr> {
    (host, port)
        .to_socket_addrs()?
        .find(|addr| addr.is_ipv4())
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no IPv4 address resolved"))
}

async fn read_target(client: &mut TcpStream, address_type: u8) -> io::Result<SocketAddr> {
    let host = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets)).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            String::from_utf8(host).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid socks host"))?
        }
        0x04 => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "IPv6 SOCKS targets are not supported by the current WARP runtime",
            ));
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks address type"));
        }
    };
    let mut port = [0u8; 2];
    client.read_exact(&mut port).await?;
    let port = u16::from_be_bytes(port);
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        Ok(SocketAddr::V4(SocketAddrV4::new(ip, port)))
    } else {
        lookup_host((host.as_str(), port))
            .await?
            .find(|addr| addr.is_ipv4())
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve IPv4 target"))
    }
}

fn parse_socks_udp_request(buffer: &[u8]) -> anyhow::Result<(SocketAddr, Bytes)> {
    if buffer.len() < 10 {
        anyhow::bail!("socks udp datagram too short");
    }
    if buffer[2] != 0 {
        anyhow::bail!("fragmented socks udp datagrams are unsupported");
    }
    let address_type = buffer[3];
    let (target, payload_offset) = match address_type {
        0x01 => {
            let ip = Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);
            let port = u16::from_be_bytes([buffer[8], buffer[9]]);
            (SocketAddr::V4(SocketAddrV4::new(ip, port)), 10)
        }
        _ => anyhow::bail!("only ipv4 socks udp targets are supported"),
    };
    Ok((target, Bytes::copy_from_slice(&buffer[payload_offset..])))
}

fn encode_socks_udp_response(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    match target {
        SocketAddr::V4(addr) => {
            let mut packet = Vec::with_capacity(10 + payload.len());
            packet.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
            packet.extend_from_slice(payload);
            packet
        }
        SocketAddr::V6(_) => payload.to_vec(),
    }
}

async fn resolve_endpoint(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<SocketAddr> {
    let port = u16::try_from(endpoint.port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid WARP endpoint port"))?;
    if let Some(ipv4) = endpoint.ipv4.as_deref().filter(|value| !value.is_empty()) {
        return resolve_sync_host(ipv4, port);
    }
    resolve_sync_host(&endpoint.host, port)
}

fn parse_ipv4_cidr(value: Option<&str>) -> Option<IpAddr> {
    value.and_then(|raw| raw.split('/').next()).and_then(|addr| addr.parse::<Ipv4Addr>().ok()).map(IpAddr::V4)
}

fn decode_key(value: &str) -> anyhow::Result<[u8; 32]> {
    let bytes = decode(value).context("base64 decode failed")?;
    bytes.try_into().map_err(|_| anyhow!("expected 32-byte key"))
}

fn reserved_bytes_from_client_id(client_id: Option<&str>) -> [u8; 3] {
    let mut reserved = [0u8; 3];
    if let Some(client_id) = client_id {
        if let Ok(decoded) = decode(client_id) {
            for (index, value) in decoded.iter().take(3).enumerate() {
                reserved[index] = *value;
            }
        }
    }
    reserved
}

fn apply_reserved_bytes(packet: &mut [u8], reserved: [u8; 3]) {
    if packet.len() >= 4 {
        packet[1..4].copy_from_slice(&reserved);
    }
}

fn route_protocol(packet: &[u8], source_peer_ip: IpAddr) -> Option<PortProtocol> {
    match IpVersion::of_packet(packet).ok()? {
        IpVersion::Ipv4 => {
            let packet = Ipv4Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
        IpVersion::Ipv6 => {
            let packet = Ipv6Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
    }
}

async fn write_reply(client: &mut TcpStream, code: u8, bind_addr: SocketAddr) -> io::Result<()> {
    match bind_addr {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, code, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            client.write_all(&reply).await
        }
        SocketAddr::V6(_) => Err(io::Error::new(io::ErrorKind::Unsupported, "ipv6 bind replies are unsupported")),
    }
}

fn to_io_error(error: anyhow::Error) -> io::Error {
    io::Error::new(io::ErrorKind::Other, error.to_string())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_id_reserved_bytes_are_padded() {
        assert_eq!(reserved_bytes_from_client_id(Some("AQID")), [1, 2, 3]);
        assert_eq!(reserved_bytes_from_client_id(Some("AQI=")), [1, 2, 0]);
        assert_eq!(reserved_bytes_from_client_id(Some("%%%")), [0, 0, 0]);
    }

    #[test]
    fn reserved_bytes_patch_header() {
        let mut packet = vec![1, 0, 0, 0, 9];
        apply_reserved_bytes(&mut packet, [7, 8, 9]);
        assert_eq!(&packet[..4], &[1, 7, 8, 9]);
    }
}
