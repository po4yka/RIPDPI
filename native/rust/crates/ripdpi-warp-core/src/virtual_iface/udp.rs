use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, SocketAddr};

use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::udp;
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpVersion};
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort};

use super::bus::{Bus, Event};
use super::device::VirtualIpDevice;
use super::socket_factory::new_udp_client_socket;

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
                        if client_socket.can_send()
                            && let Some(queue) = send_queue.get_mut(virtual_port)
                                && let Some((target, data)) = queue.pop_front() {
                                    let _ = client_socket.send_slice(&data, udp::UdpMetadata::from(target));
                                }
                        if client_socket.can_recv()
                            && let Ok((data, _peer)) = client_socket.recv()
                                && !data.is_empty() {
                                    endpoint.send(Event::RemoteData(*virtual_port, Bytes::copy_from_slice(data)));
                                }
                    }
                    next_poll = iface
                        .poll_delay(loop_start, &sockets)
                        .map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::LocalData(port_forward, virtual_port, data) if matches!(port_forward.protocol, PortProtocol::Udp) => {
                        let dest_ip = IpAddress::from(port_forward.destination.ip());
                        if ip_addrs.insert(dest_ip) {
                            iface.update_ip_addrs(|addrs| {
                                let prefix = match dest_ip.version() {
                                    IpVersion::Ipv4 => 32,
                                    IpVersion::Ipv6 => 128,
                                };
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
        Ok(())
    }
}
