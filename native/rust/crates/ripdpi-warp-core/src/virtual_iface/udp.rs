use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, Ipv6Addr, SocketAddr};

use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::udp;
use smoltcp::time::Instant;
use smoltcp::wire::HardwareAddress;
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort};

use super::bus::{Bus, BusEndpoint, Event};
use super::device::VirtualIpDevice;
use super::socket_factory::{configure_interface, new_udp_client_socket, select_source};

pub(crate) struct DynamicUdpInterface {
    bus: Bus,
    // Subscribe during construction so LocalData survives until run is first polled.
    endpoint: BusEndpoint,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    mtu: usize,
}

impl DynamicUdpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, source_peer_ipv6: Option<Ipv6Addr>, mtu: usize) -> Self {
        let endpoint = bus.new_endpoint();
        Self { bus, endpoint, source_peer_ip, source_peer_ipv6, mtu }
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the receive arm consumes bus packets
    /// and polling mutates virtual sockets. The receive and timer waits in
    /// `select!` are individually cancel-safe when another arm completes.
    /// Terminal cancellation drops this interface, its startup subscription, and
    /// all owned virtual sockets.
    pub(crate) async fn run(self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Udp, self.bus.clone(), self.mtu);
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, Instant::now());
        configure_interface(&mut iface, self.source_peer_ip, self.source_peer_ipv6)?;
        let mut endpoint = self.endpoint;
        let mut next_poll: Option<tokio::time::Instant> = None;
        // Set by events that need a poll now. An idle association then waits for the
        // next event or smoltcp deadline instead of re-polling on every timer tick.
        let mut poll_now = false;
        let mut port_client_handle_map: HashMap<(VirtualPort, IpAddr), SocketHandle> = HashMap::new();
        let mut send_queue: HashMap<(VirtualPort, IpAddr), VecDeque<(SocketAddr, Bytes)>> = HashMap::new();

        loop {
            tokio::select! {
                _ = match (poll_now, next_poll) {
                    (true, _) => tokio::time::sleep(Duration::ZERO),
                    (false, None) => tokio::time::sleep(Duration::MAX),
                    (false, Some(until)) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    for (key, client_handle) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<udp::Socket>(*client_handle);
                        // Move every queued and every received datagram per pass: the metadata
                        // ring holds only a burst, and a later pass may wait for the next event.
                        if let Some(queue) = send_queue.get_mut(key) {
                            while client_socket.can_send()
                                && let Some((target, data)) = queue.pop_front()
                            {
                                // An unaddressable datagram is dropped; a full buffer waits.
                                if let Err(udp::SendError::BufferFull) =
                                    client_socket.send_slice(&data, udp::UdpMetadata::from(target))
                                {
                                    queue.push_front((target, data));
                                    break;
                                }
                            }
                        }
                        while let Ok((data, peer)) = client_socket.recv() {
                            if !data.is_empty() {
                                let source = SocketAddr::new(peer.endpoint.addr.into(), peer.endpoint.port);
                                endpoint.send(Event::RemoteUdpDatagram(key.0, source, Bytes::copy_from_slice(data)));
                            }
                        }
                    }
                    // Datagrams left in a send queue or a receive buffer need another pass now.
                    poll_now = port_client_handle_map.iter().any(|(key, handle)| {
                        send_queue.get(key).is_some_and(|queue| !queue.is_empty())
                            || sockets.get::<udp::Socket>(*handle).can_recv()
                    });
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
                            // A bind error fails only this datagram, never the shared interface.
                            let socket = match new_udp_client_socket(source, virtual_port) {
                                Ok(socket) => socket,
                                Err(error) => {
                                    tracing::debug!(%error, "UDP virtual bind refused");
                                    continue;
                                }
                            };
                            let socket_handle = sockets.add(socket);
                            entry.insert(socket_handle);
                            send_queue.insert(key, VecDeque::new());
                        }
                        send_queue
                            .get_mut(&key)
                            .expect("udp queue exists")
                            .push_back((port_forward.destination, data));
                        poll_now = true;
                    }
                    Event::ClientConnectionDropped(port) => {
                        remove_client(&mut sockets, &mut port_client_handle_map, &mut send_queue, port);
                        poll_now = true;
                    }
                    Event::VirtualDeviceFed(PortProtocol::Udp) => poll_now = true,
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
    /// Run `interface` while `exchange` drives it, counting how often the interface task is polled.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: `exchange` must shut the bus down so the interface returns.
    async fn run_counting_polls<F: std::future::Future>(
        interface: DynamicUdpInterface,
        polls: &std::sync::atomic::AtomicUsize,
        exchange: F,
    ) -> (anyhow::Result<()>, F::Output) {
        let mut run = std::pin::pin!(interface.run());
        let counted = std::future::poll_fn(|cx| {
            polls.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            run.as_mut().poll(cx)
        });
        tokio::join!(counted, exchange)
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the interface is shut down only after the exchange.
    #[tokio::test(flavor = "current_thread")]
    async fn idle_association_does_not_keep_the_interface_polling() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let interface = DynamicUdpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
        let target = crate::ports::PortForwardConfig {
            destination: "10.77.0.1:41002".parse().expect("target"),
            protocol: PortProtocol::Udp,
        };
        bus.new_endpoint().send(Event::LocalData(
            target,
            VirtualPort::new(1000, PortProtocol::Udp),
            Bytes::from_static(b"q"),
        ));
        let polls = AtomicUsize::new(0);

        let (run, idle_polls) = run_counting_polls(interface, &polls, async {
            tokio::time::timeout(Duration::from_secs(1), async {
                while !matches!(observer.recv().await, Event::OutboundInternetPacket(_)) {}
            })
            .await
            .expect("the datagram goes out");
            let before = polls.load(Ordering::Relaxed);
            tokio::time::sleep(Duration::from_millis(200)).await;
            let idle_polls = polls.load(Ordering::Relaxed) - before;
            bus.shutdown();
            idle_polls
        })
        .await;
        run.expect("interface exits on shutdown");
        assert!(idle_polls < 10, "an idle association polled the interface {idle_polls} times in 200 ms");
    }

    fn inbound_udp_datagram(payload: &[u8]) -> Bytes {
        use smoltcp::phy::ChecksumCapabilities;
        use smoltcp::wire::{IpAddress, IpProtocol, Ipv4Packet, Ipv4Repr, UdpPacket, UdpRepr};
        let (src, dst) = ("10.77.0.1".parse().expect("src"), "10.77.0.2".parse().expect("dst"));
        let udp = UdpRepr { src_port: 41002, dst_port: 1000 };
        let ip = Ipv4Repr {
            src_addr: src,
            dst_addr: dst,
            next_header: IpProtocol::Udp,
            payload_len: udp.header_len() + payload.len(),
            hop_limit: 64,
        };
        let caps = ChecksumCapabilities::default();
        let mut buffer = vec![0u8; ip.buffer_len() + ip.payload_len];
        ip.emit(&mut Ipv4Packet::new_unchecked(&mut buffer[..]), &caps);
        udp.emit(
            &mut UdpPacket::new_unchecked(&mut buffer[ip.buffer_len()..]),
            &IpAddress::Ipv4(src),
            &IpAddress::Ipv4(dst),
            payload.len(),
            |out| out.copy_from_slice(payload),
            &caps,
        );
        Bytes::from(buffer)
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the interface is shut down only after the exchange.
    #[tokio::test(flavor = "current_thread")]
    async fn burst_of_inbound_datagrams_reaches_the_association_whole() {
        const BURST: usize = 20;
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let interface = DynamicUdpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
        let target = crate::ports::PortForwardConfig {
            destination: "10.77.0.1:41002".parse().expect("target"),
            protocol: PortProtocol::Udp,
        };
        let sender = bus.new_endpoint();
        sender.send(Event::LocalData(target, VirtualPort::new(1000, PortProtocol::Udp), Bytes::from_static(b"q")));

        let (run, received) = tokio::join!(interface.run(), async {
            let received = tokio::time::timeout(Duration::from_secs(1), async {
                while !matches!(observer.recv().await, Event::OutboundInternetPacket(_)) {}
                // One burst lands in the device queue before the interface polls it.
                for index in 0..BURST {
                    sender.send(Event::InboundInternetPacket(
                        PortProtocol::Udp,
                        inbound_udp_datagram(&[index as u8; 1200]),
                    ));
                }
                let mut received = 0;
                while received < BURST {
                    if let Event::RemoteUdpDatagram(..) = observer.recv().await {
                        received += 1;
                    }
                }
                received
            })
            .await;
            bus.shutdown();
            received
        });
        run.expect("interface exits on shutdown");
        assert_eq!(received.ok(), Some(BURST), "every datagram of the burst must reach the association");
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the interface is shut down only after the exchange.
    #[tokio::test]
    async fn refused_bind_drops_only_its_datagram() {
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let interface = DynamicUdpInterface::new(bus.clone(), "10.77.0.2".parse().expect("source"), None, 1280);
        let sender = bus.new_endpoint();
        let target = crate::ports::PortForwardConfig {
            destination: "10.77.0.1:41002".parse().expect("target"),
            protocol: PortProtocol::Udp,
        };
        // smoltcp refuses to bind local port 0.
        sender.send(Event::LocalData(target, VirtualPort::new(0, PortProtocol::Udp), Bytes::from_static(b"refused")));
        sender.send(Event::LocalData(target, VirtualPort::new(1000, PortProtocol::Udp), Bytes::from_static(b"valid")));

        let (run, outbound) = tokio::join!(interface.run(), async {
            let outcome = tokio::time::timeout(Duration::from_secs(1), async {
                loop {
                    if let Event::OutboundInternetPacket(packet) = observer.recv().await {
                        break packet;
                    }
                }
            })
            .await;
            bus.shutdown();
            outcome
        });
        run.expect("a refused bind must not stop the interface");
        let packet = outbound.expect("the valid datagram still goes out");
        let ip = smoltcp::wire::Ipv4Packet::new_checked(packet.as_ref()).expect("IPv4 packet");
        let udp = smoltcp::wire::UdpPacket::new_checked(ip.payload()).expect("UDP packet");
        assert_eq!(udp.src_port(), 1000);
        assert_eq!(udp.payload(), b"valid");
    }

    /// # Cancel safety:
    /// NOT cancel-safe: cancelling the joined exchange skips
    /// shutdown. Normal success and timeout send shutdown and join the interface
    /// before assertions; terminal cancellation requires test-runtime teardown
    /// to stop the virtual device's spawned worker.
    #[tokio::test]
    async fn datagram_queued_during_startup_reaches_the_real_udp_interface() {
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let source: IpAddr = "10.77.0.2".parse().expect("source");
        let target: SocketAddr = "10.77.0.1:41002".parse().expect("target");
        let interface = DynamicUdpInterface::new(bus.clone(), source, None, 1280);
        let sender = bus.new_endpoint();

        // The runtime has constructed the interface and spawned its future, but
        // the scheduler has not polled that future when SOCKS emits this event.
        sender.send(Event::LocalData(
            crate::ports::PortForwardConfig { destination: target, protocol: PortProtocol::Udp },
            VirtualPort::new(1000, PortProtocol::Udp),
            Bytes::from_static(b"startup-datagram"),
        ));

        let (run, outbound) = tokio::join!(interface.run(), async {
            let outcome = tokio::time::timeout(Duration::from_secs(1), async {
                loop {
                    if let Event::OutboundInternetPacket(packet) = observer.recv().await {
                        break packet;
                    }
                }
            })
            .await;
            bus.shutdown();
            outcome
        });
        run.expect("virtual interface exits after owned shutdown");
        let packet = outbound.expect("a pre-poll datagram must not disappear at readiness");
        let ip = smoltcp::wire::Ipv4Packet::new_checked(packet.as_ref()).expect("real IPv4 packet");
        assert_eq!(ip.src_addr(), "10.77.0.2".parse::<std::net::Ipv4Addr>().expect("source"));
        assert_eq!(ip.dst_addr(), "10.77.0.1".parse::<std::net::Ipv4Addr>().expect("target"));
        let udp = smoltcp::wire::UdpPacket::new_checked(ip.payload()).expect("real UDP packet");
        assert_eq!(udp.src_port(), 1000);
        assert_eq!(udp.dst_port(), 41002);
        assert_eq!(udp.payload(), b"startup-datagram");
    }
}
