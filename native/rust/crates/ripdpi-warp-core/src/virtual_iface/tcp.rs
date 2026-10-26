use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, Ipv6Addr};
use std::sync::Arc;

use anyhow::Context;
use bytes::Bytes;
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress};
use tokio::sync::{Notify, Semaphore, mpsc, oneshot};
use tokio::time::Duration;

use crate::ports::{PortProtocol, VirtualPort, VirtualPortLease};

use super::bus::{Bus, Event, TcpCommand};
use super::device::VirtualIpDevice;
use super::socket_factory::{configure_interface, new_tcp_client_socket, select_source};

#[cfg(test)]
mod tests;

pub(crate) struct DynamicTcpInterface {
    bus: Bus,
    commands: mpsc::UnboundedReceiver<TcpCommand>,
    drained: Arc<Notify>,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    mtu: usize,
    close_grace: Duration,
    /// A connect or send the peer leaves unanswered this long aborts the
    /// socket; otherwise smoltcp retransmits to a dead peer forever.
    peer_timeout: Duration,
}

impl DynamicTcpInterface {
    pub(crate) fn new(bus: Bus, source_peer_ip: IpAddr, source_peer_ipv6: Option<Ipv6Addr>, mtu: usize) -> Self {
        let (commands, drained) = bus.take_tcp_commands();
        Self {
            bus,
            commands,
            drained,
            source_peer_ip,
            source_peer_ipv6,
            mtu,
            close_grace: Duration::from_secs(30),
            peer_timeout: Duration::from_secs(60),
        }
    }

    /// # Cancel safety
    /// NOT cancel-safe: interface polling consumes bus packets.
    /// Terminal cancellation drops this interface and all owned virtual sockets.
    pub(crate) async fn run(mut self) -> anyhow::Result<()> {
        let mut sockets = SocketSet::new([]);
        let mut device = VirtualIpDevice::new(PortProtocol::Tcp, self.bus.clone(), self.mtu);
        let mut config = IfaceConfig::new(HardwareAddress::Ip);
        config.random_seed = getrandom::u64().context("virtual TCP sequence entropy unavailable")?;
        let mut iface = Interface::new(config, &mut device, Instant::now());
        configure_interface(&mut iface, self.source_peer_ip, self.source_peer_ipv6)?;
        let mut endpoint = self.bus.new_endpoint();
        let mut next_poll: Option<tokio::time::Instant> = None;
        // Dropping a session's sender ends its handler after the queued chunks.
        let mut port_client_handle_map: HashMap<VirtualPort, (SocketHandle, VirtualPortLease, mpsc::Sender<Bytes>)> =
            HashMap::new();
        // Queued client bytes and the session's upload credit, returned as smoltcp takes them.
        let mut send_queue: HashMap<VirtualPort, (VecDeque<Bytes>, Arc<Semaphore>)> = HashMap::new();
        let mut graceful_closing = HashSet::new();
        // Sessions whose SOCKS reply waits for the virtual connect to complete.
        let mut pending_connect: HashMap<VirtualPort, oneshot::Sender<()>> = HashMap::new();
        // Sessions already told that the peer sent FIN.
        let mut remote_finished = HashSet::new();

        loop {
            tokio::select! {
                _ = match (next_poll, port_client_handle_map.len()) {
                    (None, 0) => tokio::time::sleep(Duration::MAX),
                    (None, _) => tokio::time::sleep(Duration::ZERO),
                    (Some(until), _) => tokio::time::sleep_until(until),
                } => {
                    let loop_start = Instant::now();
                    let close_now = tokio::time::Instant::now();
                    for (virtual_port, (client_handle, lease, _)) in &port_client_handle_map {
                        let expired = lease.close_requested_at()
                            .map_or(true, |requested| requested.is_some_and(|at| close_now >= at + self.close_grace));
                        // A handler that stopped waiting for the connect is gone: reset now
                        // instead of holding the port until the close grace ends.
                        let abandoned = pending_connect.get(virtual_port).is_some_and(oneshot::Sender::is_closed);
                        if expired || abandoned {
                            sockets.get_mut::<tcp::Socket>(*client_handle).abort();
                        }
                    }
                    // Poll aborted sockets before removal so their RST reaches the peer.
                    let _ = iface.poll(loop_start, &mut device, &mut sockets);
                    port_client_handle_map.retain(|virtual_port, (client_handle, _lease, remote)| {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        // A closed socket may still hold ACKed bytes behind a full session queue.
                        let undelivered = client_socket.can_recv() && !remote.is_closed();
                        if client_socket.state() == tcp::State::Closed && !undelivered {
                            send_queue.remove(virtual_port);
                            graceful_closing.remove(virtual_port);
                            pending_connect.remove(virtual_port);
                            remote_finished.remove(virtual_port);
                            sockets.remove(*client_handle);
                            false
                        } else {
                            true
                        }
                    });

                    for (virtual_port, (client_handle, _lease, remote)) in &port_client_handle_map {
                        let client_socket = sockets.get_mut::<tcp::Socket>(*client_handle);
                        if client_socket.may_send()
                            && let Some(connected) = pending_connect.remove(virtual_port)
                        {
                            let _ = connected.send(());
                        }
                        if client_socket.can_send()
                            && let Some((queue, upload)) = send_queue.get_mut(virtual_port) {
                                match queue.pop_front() { Some(to_send) => {
                                    let total = to_send.len();
                                    match client_socket.send_slice(&to_send) {
                                        Ok(sent) => {
                                            upload.add_permits(sent);
                                            if sent < total {
                                                queue.push_front(to_send.slice(sent..));
                                            }
                                        }
                                        // Skipping accepted client bytes would corrupt the
                                        // stream, so reset the session instead.
                                        Err(error) => {
                                            tracing::warn!(%error, "TCP virtual send failed; resetting the session");
                                            upload.add_permits(total);
                                            client_socket.abort();
                                        }
                                    }
                                // A peer FIN closes only the peer's direction: our FIN waits
                                // until the client finishes too (RFC 9293 section 3.6).
                                } _ => if graceful_closing.contains(virtual_port) {
                                    client_socket.close();
                                }}
                            }
                        if client_socket.can_recv() {
                            if remote.is_closed() {
                                // The handler is gone: discard so the peer is not stalled.
                                let _ = client_socket.recv(|buffer| (buffer.len(), ()));
                            } else if let Ok(permit) = remote.try_reserve()
                                && let Ok(data) = client_socket.recv(|buffer| (buffer.len(), Bytes::copy_from_slice(buffer)))
                                && !data.is_empty() {
                                    // Read only into reserved queue space: a slow handler then closes
                                    // the TCP window instead of losing bytes smoltcp already ACKed.
                                    permit.send(data);
                                }
                        }
                        // After the peer's FIN and all data before it, tell the handler with an
                        // empty chunk so it can half-close the client.
                        if !client_socket.may_recv()
                            && !client_socket.can_recv()
                            && !pending_connect.contains_key(virtual_port)
                            && !remote_finished.contains(virtual_port)
                            && let Ok(permit) = remote.try_reserve()
                        {
                            permit.send(Bytes::new());
                            remote_finished.insert(*virtual_port);
                        }
                    }
                    next_poll = iface
                        .poll_delay(loop_start, &sockets)
                        .map(|delay| tokio::time::Instant::now() + Duration::from_millis(delay.total_millis()));
                    if !port_client_handle_map.is_empty() {
                        // Close deadlines and a missed drain wakeup are rechecked on this
                        // bounded period.
                        let control_poll = tokio::time::Instant::now() + Duration::from_millis(100);
                        next_poll = Some(next_poll.map_or(control_poll, |poll| poll.min(control_poll)));
                    }
                }
                command = self.commands.recv() => match command {
                    // The bus keeps a sender, so the channel stays open while this task runs.
                    None => break,
                    Some(TcpCommand::Open(port_forward, lease, remote, upload, connected)) => {
                        let virtual_port = lease.port();
                        // Without a source the session is dropped, which ends its handler.
                        let Some(source) = select_source(self.source_peer_ip, self.source_peer_ipv6, port_forward.destination.ip()) else {
                            continue;
                        };
                        let mut client_socket = new_tcp_client_socket();
                        client_socket.set_timeout(Some(smoltcp::time::Duration::from_millis(
                            u64::try_from(self.peer_timeout.as_millis()).unwrap_or(u64::MAX),
                        )));
                        // A refused target (port 0, unspecified address) fails only this
                        // session: dropping `remote` ends its handler.
                        if let Err(error) = client_socket.connect(
                            iface.context(),
                            (IpAddress::from(port_forward.destination.ip()), port_forward.destination.port()),
                            (IpAddress::from(source), virtual_port.num()),
                        ) {
                            tracing::debug!(%error, "TCP virtual connect refused");
                            continue;
                        }
                        let client_handle = sockets.add(client_socket);
                        port_client_handle_map.insert(virtual_port, (client_handle, lease, remote));
                        pending_connect.insert(virtual_port, connected);
                        send_queue.insert(virtual_port, (VecDeque::new(), upload));
                        next_poll = None;
                    }
                    Some(TcpCommand::Close(virtual_port)) => {
                        if let Some((_, lease, _)) = port_client_handle_map.get(&virtual_port) {
                            lease.request_close();
                            // This ordered marker follows every accepted Data command.
                            // The shared timestamp alone may overtake queued data.
                            graceful_closing.insert(virtual_port);
                            next_poll = None;
                        }
                    }
                    Some(TcpCommand::Data(virtual_port, data)) => {
                        if let Some((queue, _)) = send_queue.get_mut(&virtual_port) {
                            queue.push_back(data);
                            next_poll = None;
                        }
                    }
                },
                () = self.drained.notified() => next_poll = None,
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::VirtualDeviceFed(PortProtocol::Tcp) => next_poll = None,
                    _ => {}
                }
            }
        }
        Ok(())
    }
}
