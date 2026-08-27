use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, Ipv6Addr, SocketAddr};

use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::udp;
use smoltcp::time::Instant;
use smoltcp::wire::HardwareAddress;
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort};

use super::bus::{Bus, Event};
use super::device::VirtualIpDevice;
use super::socket_factory::{configure_interface, new_udp_client_socket, select_source};

pub(crate) struct DynamicUdpInterface {
    bus: Bus,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    mtu: usize,
}

impl DynamicUdpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, source_peer_ipv6: Option<Ipv6Addr>, mtu: usize) -> Self {
        Self { bus, source_peer_ip, source_peer_ipv6, mtu }
    }

    /// # Cancel safety
    /// Not cancel-safe for continuation: interface polling consumes bus packets.
    /// Terminal cancellation drops this interface and all owned virtual sockets.
    // NOT cancel-safe: the runtime aborts only during complete interface teardown.
    pub(crate) async fn run(self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Udp, self.bus.clone(), self.mtu);
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, Instant::now());
        configure_interface(&mut iface, self.source_peer_ip, self.source_peer_ipv6)?;
        let mut endpoint = self.bus.new_endpoint();
        let mut next_poll: Option<tokio::time::Instant> = None;
        let mut port_client_handle_map: HashMap<(VirtualPort, IpAddr), SocketHandle> = HashMap::new();
        let mut send_queue: HashMap<(VirtualPort, IpAddr), VecDeque<(SocketAddr, Bytes)>> = HashMap::new();

        loop {
            tokio::select! {
                _ = match (next_poll, port_client_handle_map.len()) {
                    (None, 0) => tokio::time::sleep(Duration::MAX),
                    (None, _) => tokio::time::sleep(Duration::ZERO),
                    (Some(until), _) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    for (key, client_handle) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<udp::Socket>(*client_handle);
                        if client_socket.can_send()
                            && let Some(queue) = send_queue.get_mut(key)
                                && let Some((target, data)) = queue.pop_front() {
                                    let _ = client_socket.send_slice(&data, udp::UdpMetadata::from(target));
                                }
                        if client_socket.can_recv()
                            && let Ok((data, peer)) = client_socket.recv()
                                && !data.is_empty() {
                                    let source = SocketAddr::new(peer.endpoint.addr.into(), peer.endpoint.port);
                                    endpoint.send(Event::RemoteUdpDatagram(key.0, source, Bytes::copy_from_slice(data)));
                                }
                    }
                    next_poll = iface
                        .poll_delay(loop_start, &sockets)
                        .map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::LocalData(port_forward, virtual_port, data) if matches!(port_forward.protocol, PortProtocol::Udp) => {
                        let Some(source) = select_source(self.source_peer_ip, self.source_peer_ipv6, port_forward.destination.ip()) else {
                            continue;
                        };
                        let key = (virtual_port, source);
                        if let std::collections::hash_map::Entry::Vacant(entry) = port_client_handle_map.entry(key) {
                            let socket_handle = sockets.add(new_udp_client_socket(source, virtual_port)?);
                            entry.insert(socket_handle);
                            send_queue.insert(key, VecDeque::new());
                        }
                        send_queue
                            .get_mut(&key)
                            .expect("udp queue exists")
                            .push_back((port_forward.destination, data));
                        next_poll = None;
                    }
                    Event::ClientConnectionDropped(port) => {
                        remove_client(&mut sockets, &mut port_client_handle_map, &mut send_queue, port);
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

fn remove_client(
    sockets: &mut SocketSet<'_>,
    handles: &mut HashMap<(VirtualPort, IpAddr), SocketHandle>,
    queues: &mut HashMap<(VirtualPort, IpAddr), VecDeque<(SocketAddr, Bytes)>>,
    port: VirtualPort,
) {
    handles.retain(|key, handle| {
        if key.0 == port {
            sockets.remove(*handle);
            queues.remove(key);
            false
        } else {
            true
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dropping_association_removes_both_families_and_preserves_other_clients() {
        let first = VirtualPort::new(1000, PortProtocol::Udp);
        let second = VirtualPort::new(1001, PortProtocol::Udp);
        let v4: IpAddr = "10.77.0.2".parse().expect("v4");
        let v6: IpAddr = "fd77::2".parse().expect("v6");
        let mut sockets = SocketSet::new([]);
        let mut handles = HashMap::new();
        let mut queues = HashMap::new();
        for key in [(first, v4), (first, v6), (second, v4)] {
            handles.insert(key, sockets.add(new_udp_client_socket(key.1, key.0).expect("UDP socket")));
            queues.insert(key, VecDeque::from([(SocketAddr::new(key.1, 53), Bytes::from_static(b"queued"))]));
        }
        remove_client(&mut sockets, &mut handles, &mut queues, first);
        assert_eq!(sockets.iter().count(), 1, "both family sockets must close");
        assert_eq!(handles.len(), 1);
        assert_eq!(queues.len(), 1);
        assert!(handles.contains_key(&(second, v4)));
        assert!(queues.contains_key(&(second, v4)));
        remove_client(&mut sockets, &mut handles, &mut queues, first);
        assert_eq!(sockets.iter().count(), 1, "cleanup is idempotent");
    }
}
