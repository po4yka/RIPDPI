use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use anyhow::Context;
use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{DeviceCapabilities, Medium};
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpVersion};
use tokio::sync::broadcast;
use tokio::time::Duration;

use crate::ports::{PortForwardConfig, PortProtocol, VirtualPort};
use crate::support::MAX_PACKET;

#[derive(Debug, Clone)]
pub(crate) enum Event {
    ClientConnectionInitiated(PortForwardConfig, VirtualPort),
    ClientConnectionDropped(VirtualPort),
    LocalData(PortForwardConfig, VirtualPort, Bytes),
    RemoteData(VirtualPort, Bytes),
    InboundInternetPacket(PortProtocol, Bytes),
    OutboundInternetPacket(Bytes),
    VirtualDeviceFed(PortProtocol),
}

#[derive(Clone)]
pub(crate) struct Bus {
    counter: Arc<AtomicU64>,
    tx: broadcast::Sender<(u64, Event)>,
}

impl Bus {
    pub(crate) fn new() -> Self {
        let (tx, _) = broadcast::channel(1024);
        Self { counter: Arc::new(AtomicU64::new(0)), tx }
    }

    pub(crate) fn new_endpoint(&self) -> BusEndpoint {
        let id = self.counter.fetch_add(1, Ordering::Relaxed);
        BusEndpoint { id, tx: self.tx.clone(), rx: self.tx.subscribe() }
    }
}

pub(crate) struct BusEndpoint {
    id: u64,
    tx: broadcast::Sender<(u64, Event)>,
    rx: broadcast::Receiver<(u64, Event)>,
}

impl BusEndpoint {
    pub(crate) fn send(&self, event: Event) {
        let _ = self.tx.send((self.id, event));
    }

    pub(crate) async fn recv(&mut self) -> Event {
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
pub(crate) struct DynamicTcpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    mtu: usize,
}

impl DynamicTcpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, mtu: usize) -> Self {
        Self { bus, source_peer_ip, mtu }
    }

    pub(crate) async fn run(self) -> anyhow::Result<()> {
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

pub(crate) struct DynamicUdpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    mtu: usize,
}

impl DynamicUdpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, mtu: usize) -> Self {
        Self { bus, source_peer_ip, mtu }
    }

    pub(crate) async fn run(self) -> anyhow::Result<()> {
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
