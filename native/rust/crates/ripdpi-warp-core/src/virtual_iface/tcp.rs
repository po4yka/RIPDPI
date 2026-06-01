use std::collections::{HashMap, HashSet, VecDeque};
use std::net::IpAddr;

use anyhow::Context;
use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpVersion};
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort};

use super::bus::{Bus, Event};
use super::device::VirtualIpDevice;
use super::socket_factory::new_tcp_client_socket;

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
                        if client_socket.can_send()
                            && let Some(queue) = send_queue.get_mut(virtual_port) {
                                match queue.pop_front() { Some(to_send) => {
                                    let total = to_send.len();
                                    if let Ok(sent) = client_socket.send_slice(&to_send)
                                        && sent < total {
                                            queue.push_front(Bytes::copy_from_slice(&to_send[sent..]));
                                        }
                                } _ => if client_socket.state() == tcp::State::CloseWait {
                                    client_socket.close();
                                }}
                            }
                        if client_socket.can_recv()
                            && let Ok(data) = client_socket.recv(|buffer| (buffer.len(), Bytes::copy_from_slice(buffer)))
                                && !data.is_empty() {
                                    endpoint.send(Event::RemoteData(*virtual_port, data));
                                }
                    }
                    next_poll = iface
                        .poll_delay(loop_start, &sockets)
                        .map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::ClientConnectionInitiated(port_forward, virtual_port) if matches!(port_forward.protocol, PortProtocol::Tcp) => {
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
        Ok(())
    }
}
