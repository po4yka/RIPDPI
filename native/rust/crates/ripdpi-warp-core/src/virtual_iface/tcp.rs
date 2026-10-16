use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, Ipv6Addr};

use anyhow::Context;
use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress};
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort, VirtualPortLease};

use super::bus::{Bus, Event};
use super::device::VirtualIpDevice;
use super::socket_factory::{configure_interface, new_tcp_client_socket, select_source};

#[cfg(test)]
mod tests;

pub(crate) struct DynamicTcpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    mtu: usize,
    close_grace: Duration,
}

impl DynamicTcpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, source_peer_ipv6: Option<Ipv6Addr>, mtu: usize) -> Self {
        Self { bus, source_peer_ip, source_peer_ipv6, mtu, close_grace: Duration::from_secs(30) }
    }

    /// # Cancel safety
    /// Not cancel-safe for continuation: interface polling consumes bus packets.
    /// Terminal cancellation drops this interface and all owned virtual sockets.
    // NOT cancel-safe: the runtime aborts only during complete interface teardown.
    pub(crate) async fn run(self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Tcp, self.bus.clone(), self.mtu);
        let mut config = IfaceConfig::new(HardwareAddress::Ip);
        config.random_seed = getrandom::u64().context("virtual TCP sequence entropy unavailable")?;
        let mut iface = Interface::new(config, &mut device, Instant::now());
        configure_interface(&mut iface, self.source_peer_ip, self.source_peer_ipv6)?;
        let mut endpoint = self.bus.new_endpoint();
        let mut next_poll: Option<tokio::time::Instant> = None;
        let mut port_client_handle_map: HashMap<VirtualPort, (SocketHandle, VirtualPortLease)> = HashMap::new();
        let mut send_queue: HashMap<VirtualPort, VecDeque<Bytes>> = HashMap::new();
        let mut graceful_closing = HashSet::new();

        loop {
            tokio::select! {
                _ = match (next_poll, port_client_handle_map.len()) {
                    (None, 0) => tokio::time::sleep(Duration::MAX),
                    (None, _) => tokio::time::sleep(Duration::ZERO),
                    (Some(until), _) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    let close_now = tokio::time::Instant::now();
                    for (client_handle, lease) in port_client_handle_map.values() {
                        let expired = lease.close_requested_at()
                            .map_or(true, |requested| requested.is_some_and(|at| close_now >= at + self.close_grace));
                        if expired {
                            sockets.get_mut::<tcp::Socket>(*client_handle).abort();
                        }
                    }
                    // Poll aborted sockets before removal so their RST reaches the peer.
                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    port_client_handle_map.retain(|virtual_port, (client_handle, _lease)| {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        if client_socket.state() == tcp::State::Closed {
                            endpoint.send(Event::ClientConnectionDropped(*virtual_port));
                            send_queue.remove(virtual_port);
                            graceful_closing.remove(virtual_port);
                            sockets.remove(*client_handle);
                            false
                        } else {
                            true
                        }
                    });

                    for (virtual_port, (client_handle, _lease)) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        if client_socket.can_send()
                            && let Some(queue) = send_queue.get_mut(virtual_port) {
                                match queue.pop_front() { Some(to_send) => {
                                    let total = to_send.len();
                                    if let Ok(sent) = client_socket.send_slice(&to_send)
                                        && sent < total {
                                            queue.push_front(Bytes::copy_from_slice(&to_send[sent..]));
                                        }
                                } _ => if client_socket.state() == tcp::State::CloseWait || graceful_closing.contains(virtual_port) {
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
                    if !port_client_handle_map.is_empty() {
                        // EOF is stored in the lease; a lost broadcast wakeup cannot
                        // suppress close processing or its absolute cleanup deadline.
                        let control_poll = tokio::time::Instant::now() + Duration::from_millis(100);
                        next_poll = Some(next_poll.map_or(control_poll, |poll| poll.min(control_poll)));
                    }
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::ClientConnectionInitiated(port_forward, lease) if matches!(port_forward.protocol, PortProtocol::Tcp) => {
                        let virtual_port = lease.port();
                        let Some(source) = select_source(self.source_peer_ip, self.source_peer_ipv6, port_forward.destination.ip()) else {
                            endpoint.send(Event::ClientConnectionDropped(virtual_port));
                            continue;
                        };
                        let client_handle = sockets.add(new_tcp_client_socket());
                        port_client_handle_map.insert(virtual_port, (client_handle, lease));
                        send_queue.insert(virtual_port, VecDeque::new());
                        let client_socket = sockets.get_mut::<tcp::Socket>(client_handle);
                        client_socket
                            .connect(
                                iface.context(),
                                (IpAddress::from(port_forward.destination.ip()), port_forward.destination.port()),
                                (IpAddress::from(source), virtual_port.num()),
                            )
                            .context("TCP virtual connect failed")?;
                        next_poll = None;
                    }
                    Event::ClientConnectionDropped(virtual_port) => {
                        if let Some((_, lease)) = port_client_handle_map.get(&virtual_port) {
                            lease.request_close();
                            // This ordered marker follows every accepted LocalData event.
                            // The shared timestamp alone may overtake queued bus data.
                            graceful_closing.insert(virtual_port);
                            next_poll = None;
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
