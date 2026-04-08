use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant as StdInstant};

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::socket::Socket;
use smoltcp::wire::{IpAddress, IpListenEndpoint};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use ripdpi_tunnel_config::Config;

use crate::dns_cache::DnsCache;
use crate::session::{Auth, TargetAddr, TcpSession};
use crate::{ActiveSessions, Stats};

use super::dns_intercept::resolve_mapped_target;
use super::packet::{endpoint_to_socketaddr, tcp_syn_flow_key, TcpFlowKey};
use super::{DUPLEX_BUF, TCP_SOCKET_BUF};

fn tcp_target_endpoint(tcp: &TcpSocket) -> Option<SocketAddr> {
    tcp.local_endpoint().map(endpoint_to_socketaddr)
}

pub(super) fn tcp_session_target_addr(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    tcp: &TcpSocket,
) -> Option<SocketAddr> {
    tcp_target_endpoint(tcp).and_then(|target| resolve_mapped_target(stats, dns_cache, target))
}

pub(super) fn socketaddr_to_listen_endpoint(addr: SocketAddr) -> IpListenEndpoint {
    let ip = match addr.ip() {
        IpAddr::V4(v4) => IpAddress::Ipv4(v4.octets().into()),
        IpAddr::V6(v6) => IpAddress::Ipv6(v6.segments().into()),
    };
    IpListenEndpoint { addr: Some(ip), port: addr.port() }
}

pub(super) fn make_auth(config: &Config) -> Auth {
    match (&config.socks5.username, &config.socks5.password) {
        (Some(u), Some(p)) => Auth::UserPass { username: u.clone(), password: p.clone() },
        _ => Auth::NoAuth,
    }
}

pub(super) fn proxy_addr(config: &Config) -> io::Result<SocketAddr> {
    let ip: IpAddr = config
        .socks5
        .address
        .parse()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid socks5.address"))?;
    Ok(SocketAddr::new(ip, config.socks5.port))
}

pub(super) fn ensure_pending_listen_for_syn(
    pkt: &[u8],
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    socket_set: &mut SocketSet<'static>,
) {
    let Some(flow_key) = tcp_syn_flow_key(pkt) else {
        return;
    };
    if let std::collections::hash_map::Entry::Vacant(entry) = pending_listens.entry(flow_key) {
        let buf = || tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]);
        let mut sock = TcpSocket::new(buf(), buf());
        if sock.listen(socketaddr_to_listen_endpoint(flow_key.dst)).is_ok() {
            let handle = socket_set.add(sock);
            entry.insert((handle, StdInstant::now()));
            debug!("Added LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
        } else {
            warn!("listen({}) failed for flow {} -> {}", flow_key.dst.port(), flow_key.src, flow_key.dst);
        }
    }
}

pub(super) fn gc_stale_pending_listens(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    socket_set: &mut SocketSet<'static>,
    timeout: Duration,
) {
    let now = StdInstant::now();
    pending_listens.retain(|flow_key, (handle, created_at)| {
        let age = now.duration_since(*created_at);
        if age <= timeout {
            return true;
        }
        debug!("GC stale LISTEN socket for flow {} -> {} (age {age:?})", flow_key.src, flow_key.dst);
        socket_set.remove(*handle);
        false
    });
}

pub(super) fn spawn_new_tcp_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
) {
    let mut new_sessions: Vec<(SocketHandle, SocketAddr, Option<u32>)> = Vec::new();
    let mut unresolvable: Vec<SocketHandle> = Vec::new();

    for (handle, socket) in socket_set.iter_mut() {
        let Socket::Tcp(tcp) = socket else { continue };
        if !tcp.may_send() || sessions.contains(handle) {
            continue;
        }

        let synthetic_ip = tcp_target_endpoint(tcp).and_then(|sa| match sa.ip() {
            IpAddr::V4(v4) => {
                let ip = u32::from(v4);
                dns_cache.as_ref()?.contains_mapped_ip(ip).then_some(ip)
            }
            IpAddr::V6(_) => None,
        });

        match tcp_session_target_addr(stats, dns_cache, tcp) {
            Some(target) => new_sessions.push((handle, target, synthetic_ip)),
            None => {
                debug!("TCP socket {:?} has no resolvable target — aborting", handle);
                tcp.abort();
                unresolvable.push(handle);
            }
        }
    }

    let remove_pending = |pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>, handle| {
        if let Some(key) = pending_listens.iter().find_map(|(k, (h, _))| (*h == handle).then_some(*k)) {
            pending_listens.remove(&key);
        }
    };

    for handle in unresolvable {
        remove_pending(pending_listens, handle);
        socket_set.remove(handle);
    }

    for (handle, target_addr, synthetic_ip) in new_sessions {
        remove_pending(pending_listens, handle);
        if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), synthetic_ip) {
            cache.pin(ip);
        }
        let (smoltcp_side, mut session_side) = tokio::io::duplex(DUPLEX_BUF);
        let child_cancel = cancel.child_token();
        let session_inst = TcpSession::new(proxy_sockaddr, auth.clone(), TargetAddr::Ip(target_addr));
        let cc = child_cancel.clone();
        let join_handle = tokio::spawn(async move { session_inst.run(&mut session_side, cc).await });
        let entry = crate::SessionEntry {
            smoltcp_side,
            cancel: child_cancel,
            handle: join_handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
            pinned_synthetic_ip: synthetic_ip,
        };
        if let Some(evicted_handle) = sessions.insert(handle, entry) {
            socket_set.remove(evicted_handle);
            debug!("Evicted session socket {:?} removed from socket_set", evicted_handle);
        }
        info!("TCP session spawned: remote={}", target_addr);
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
    use std::sync::Arc;

    use smoltcp::iface::{Interface, SocketSet};
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};
    use smoltcp::time::Instant;
    use smoltcp::wire::IpAddress;
    use tokio_util::sync::CancellationToken;

    use crate::{ActiveSessions, Stats, TunDevice};

    use super::super::packet::{build_ipv4_tcp_syn_packet, build_ipv6_tcp_syn_packet, endpoint_to_socketaddr};
    use super::super::TCP_SOCKET_BUF;
    use super::{
        ensure_pending_listen_for_syn, socketaddr_to_listen_endpoint, spawn_new_tcp_sessions, tcp_session_target_addr,
    };

    fn build_ipv4_tcp_ack_packet(
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
        src_port: u16,
        dst_port: u16,
        seq: u32,
        ack: u32,
    ) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip.octets());
        pkt[16..20].copy_from_slice(&dst_ip.octets());
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..28].copy_from_slice(&seq.to_be_bytes());
        pkt[28..32].copy_from_slice(&ack.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x10;
        let ip_checksum = super::super::packet::finalize_checksum(super::super::packet::checksum_sum(&pkt[..20]));
        pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
        let mut sum = super::super::packet::checksum_sum(&src_ip.octets());
        sum += super::super::packet::checksum_sum(&dst_ip.octets());
        sum += u32::from(6u16);
        sum += u32::from((pkt.len() - 20) as u16);
        sum += super::super::packet::checksum_sum(&pkt[20..]);
        let tcp_checksum = super::super::packet::finalize_checksum(sum);
        pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
        pkt
    }

    fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
        let ihl = ((pkt[0] & 0x0f) as usize) * 4;
        let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
        let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
        (seq, ack)
    }

    #[test]
    fn socketaddr_to_listen_endpoint_preserves_ip_and_port() {
        let ipv4 = socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443));
        let ipv6 = socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8443));

        assert_eq!(ipv4.addr, Some(IpAddress::v4(203, 0, 113, 10)));
        assert_eq!(ipv4.port, 443);
        assert_eq!(ipv6.addr, Some(IpAddress::v6(0, 0, 0, 0, 0, 0, 0, 1)));
        assert_eq!(ipv6.port, 8443);
    }

    #[test]
    fn listeners_bound_to_different_destination_ips_do_not_steal_https_flows() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2))
            .expect("default ipv4 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut first = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        let mut second = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );

        first
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)))
            .expect("first listener");
        second
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443)))
            .expect("second listener");

        let first_handle = socket_set.add(first);
        let second_handle = socket_set.add(second);

        let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(203, 0, 113, 20), 51000, 443);
        device.rx_queue.push_back(syn);

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let first_socket = socket_set.get::<TcpSocket>(first_handle);
        let second_socket = socket_set.get::<TcpSocket>(second_handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        assert_eq!(first_socket.state(), tcp::State::Listen);
        assert_eq!(second_socket.state(), tcp::State::SynReceived);
        assert_eq!(
            second_socket.local_endpoint().map(endpoint_to_socketaddr),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443))
        );
        assert_eq!(
            second_socket.remote_endpoint().map(endpoint_to_socketaddr),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 51000))
        );
        assert_eq!(
            tcp_session_target_addr(&stats, &mut dns_cache, second_socket),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443))
        );
    }

    #[test]
    fn tcp_session_target_addr_prefers_intercepted_ipv4_destination_over_client_source() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 10, 10, 10), 24)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 10, 10, 10))
            .expect("default ipv4 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut socket = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        socket
            .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443)))
            .expect("listener");

        let handle = socket_set.add(socket);
        let client = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 51000);
        let destination = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 20)), 443);
        device.rx_queue.push_back(build_ipv4_tcp_syn_packet(
            Ipv4Addr::new(10, 10, 10, 10),
            Ipv4Addr::new(203, 0, 113, 20),
            51000,
            443,
        ));

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let socket = socket_set.get::<TcpSocket>(handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let target = tcp_session_target_addr(&stats, &mut dns_cache, socket).expect("session target");

        assert_eq!(socket.remote_endpoint().map(endpoint_to_socketaddr), Some(client),);
        assert_eq!(target, destination);
        assert_ne!(target, client);
    }

    #[test]
    fn tcp_session_target_addr_prefers_intercepted_ipv6_destination_over_client_source() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        let destination_ip = Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 1);
        let client_ip = Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 2);
        let [a, b, c, d, e, f, g, h] = destination_ip.segments();
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v6(a, b, c, d, e, f, g, h), 128)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv6_route(smoltcp::wire::Ipv6Address::new(a, b, c, d, e, f, g, h))
            .expect("default ipv6 route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);

        let mut socket = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]),
        );
        let destination = SocketAddr::new(IpAddr::V6(destination_ip), 443);
        let client = SocketAddr::new(IpAddr::V6(client_ip), 51000);
        socket.listen(socketaddr_to_listen_endpoint(destination)).expect("listener");

        let handle = socket_set.add(socket);
        device.rx_queue.push_back(build_ipv6_tcp_syn_packet(client_ip, destination_ip, 51000, 443));

        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let socket = socket_set.get::<TcpSocket>(handle);
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let target = tcp_session_target_addr(&stats, &mut dns_cache, socket).expect("session target");

        assert_eq!(socket.remote_endpoint().map(endpoint_to_socketaddr), Some(client),);
        assert_eq!(target, destination);
        assert_ne!(target, client);
    }

    #[tokio::test]
    async fn spawn_new_tcp_sessions_waits_for_established_handshake() {
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2))
            .expect("default ipv4 route");
        iface.set_any_ip(true);

        let mut socket_set = SocketSet::new(vec![]);
        let mut pending_listens = HashMap::new();
        let mut sessions = ActiveSessions::new(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let auth = super::Auth::NoAuth;
        let proxy_sockaddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9);

        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = Ipv4Addr::new(127, 0, 0, 1);
        let client_port = 51000;
        let target_port = 443;

        let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, client_port, target_port);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            proxy_sockaddr,
            &auth,
            &cancel,
            &stats,
            &mut dns_cache,
        );
        assert!(sessions.is_empty(), "half-open SYN-RECEIVED sockets must not spawn upstream sessions");

        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        let ack = build_ipv4_tcp_ack_packet(client_ip, target_ip, client_port, target_port, 1, server_seq + 1);
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            proxy_sockaddr,
            &auth,
            &cancel,
            &stats,
            &mut dns_cache,
        );
        assert_eq!(sessions.len(), 1, "established sockets must spawn upstream sessions exactly once");

        let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
        for handle in handles {
            if let Some(entry) = sessions.remove(handle) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
            socket_set.remove(handle);
        }
    }
}
