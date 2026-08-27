use std::io;
use std::net::{IpAddr, SocketAddr};

use smoltcp::socket::tcp::Socket as TcpSocket;
use smoltcp::wire::{IpAddress, IpListenEndpoint};

use ripdpi_tunnel_config::Config;

use crate::session::Auth;

use super::packet::endpoint_to_socketaddr;

mod admission;
mod duplex;
mod eviction;
mod listener;
mod pin;
mod target;
mod unresolved;

pub(crate) use admission::spawn_new_tcp_sessions;
pub(crate) use listener::{
    PendingListener, ensure_pending_listen_for_syn, gc_stale_pending_listens, reconcile_pending_listeners,
};
#[cfg(test)]
pub(crate) use target::tcp_session_target_addr;

fn tcp_target_endpoint(tcp: &TcpSocket) -> Option<SocketAddr> {
    tcp.local_endpoint().map(endpoint_to_socketaddr)
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

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use smoltcp::iface::{Interface, SocketSet};
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};
    use smoltcp::time::Instant;
    use smoltcp::wire::IpAddress;
    use tokio_util::sync::CancellationToken;

    use crate::dns_cache::DnsCache;
    use crate::{ActiveSessions, Stats, TunDevice};

    use super::super::TCP_SOCKET_BUF;
    use super::super::packet::{
        build_ipv4_tcp_ack_packet, build_ipv4_tcp_syn_packet, build_ipv6_tcp_syn_packet, endpoint_to_socketaddr,
        is_injected_rst, tcp_seq_ack, tcp_syn_flow_key,
    };
    use super::{
        ensure_pending_listen_for_syn, socketaddr_to_listen_endpoint, spawn_new_tcp_sessions, tcp_session_target_addr,
    };

    static UID_ADMISSION_TEST_GUARD: Mutex<()> = Mutex::new(());

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
    fn pending_syn_admission_evicts_oldest_at_capacity() {
        let mut pending_listens = HashMap::new();
        let mut socket_set = SocketSet::new(vec![]);
        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = Ipv4Addr::new(203, 0, 113, 20);

        for offset in 0..=128u16 {
            let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, 50_000 + offset, 443);
            ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        }

        let oldest = tcp_syn_flow_key(&build_ipv4_tcp_syn_packet(client_ip, target_ip, 50_000, 443))
            .expect("valid oldest SYN flow");
        let newest = tcp_syn_flow_key(&build_ipv4_tcp_syn_packet(client_ip, target_ip, 50_128, 443))
            .expect("valid newest SYN flow");
        assert_eq!(pending_listens.len(), 128, "pending handshakes must stay within the memory budget");
        assert_eq!(socket_set.iter().count(), 128, "eviction must remove the matching smoltcp socket");
        assert!(!pending_listens.contains_key(&oldest), "the oldest pending handshake must be evicted first");
        assert!(pending_listens.contains_key(&newest), "the newest pending handshake must be admitted");
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(crate::uid_policy::PROTO_TCP, oldest.src, oldest.dst),
            ripdpi_flow_app_attribution::FlowUidLookup::Missing,
            "pressure eviction retires the old generation"
        );
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(crate::uid_policy::PROTO_TCP, newest.src, newest.dst),
            ripdpi_flow_app_attribution::FlowUidLookup::Pending,
            "new listener retains its pending job"
        );
        drop(pending_listens);
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(crate::uid_policy::PROTO_TCP, newest.src, newest.dst),
            ripdpi_flow_app_attribution::FlowUidLookup::Missing,
            "shutdown retires pending registrations"
        );
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
            tcp_session_target_addr(&stats, &mut dns_cache, None, second_socket),
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
        let target = tcp_session_target_addr(&stats, &mut dns_cache, None, socket).expect("session target");

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
        let target = tcp_session_target_addr(&stats, &mut dns_cache, None, socket).expect("session target");

        assert_eq!(socket.remote_endpoint().map(endpoint_to_socketaddr), Some(client),);
        assert_eq!(target, destination);
        assert_ne!(target, client);
    }

    #[tokio::test]
    async fn mapdns_tcp_listener_starts_local_dns_session() {
        use tokio::io::AsyncWriteExt;

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
        let mut admission_cursor = 0;
        let mut sessions = ActiveSessions::new(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut dns_cache = Some(DnsCache::new(0xC612_0000, 0xFFFE_0000, 8).expect("valid MapDNS cache"));
        let auth = super::Auth::NoAuth;
        let proxy_sockaddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9);
        let uid_policy = crate::uid_policy::UidFlowPolicy::disarmed();
        let intercept = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 53)), 53);
        let mapdns = crate::io_loop::dns_intercept::MapDnsRuntime {
            intercept_addr: intercept,
            synthetic_net: 0xC612_0000,
            synthetic_mask: 0xFFFE_0000,
            intercept_port: 53,
        };
        let (dns_tx, mut dns_rx) = tokio::sync::mpsc::channel(1);
        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let client_port = 51_053;

        let syn = build_ipv4_tcp_syn_packet(client_ip, Ipv4Addr::new(198, 18, 0, 53), client_port, 53);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        device.rx_queue.push_back(build_ipv4_tcp_ack_packet(
            client_ip,
            Ipv4Addr::new(198, 18, 0, 53),
            client_port,
            53,
            1,
            server_seq + 1,
        ));
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            Some(mapdns),
            Some(dns_tx),
            None,
        );
        assert!(resetting.is_empty());
        assert_eq!(sessions.len(), 1, "TCP/53 must use a local DNS session instead of SOCKS");

        let query = [
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, b'w', b'w', b'w', 0x07, b'e',
            b'x', b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        let (_, entry) = sessions.iter_mut().next().expect("DNS session");
        entry.smoltcp_side.write_u16(query.len() as u16).await.expect("query length");
        entry.smoltcp_side.write_all(&query).await.expect("query body");
        let request = dns_rx.recv().await.expect("intercepted DNS request");
        assert_eq!(request.query, query);
        assert!(request.tcp_reply.is_some());

        cancel.cancel();
        let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
        for handle in handles {
            if let Some(entry) = sessions.remove(handle) {
                entry.handle.abort();
            }
            socket_set.remove(handle);
        }
    }

    #[tokio::test]
    async fn spawn_new_tcp_sessions_waits_for_handshake_and_uid_resolution() {
        let _guard = UID_ADMISSION_TEST_GUARD.lock().expect("UID admission test guard");
        ripdpi_flow_app_attribution::clear();
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
        let mut admission_cursor = 0;
        let mut sessions = ActiveSessions::new(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut cache = DnsCache::new(0xC612_0000, 0xFFFE_0000, 8).expect("valid MapDNS cache");
        let real_target_ip = Ipv4Addr::LOCALHOST;
        let synthetic_target_ip =
            Ipv4Addr::from(cache.find("uid-attribution.test", u32::from(real_target_ip)).expect("synthetic mapping").0);
        let mut dns_cache = Some(cache);
        let auth = super::Auth::NoAuth;
        let proxy_sockaddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9);
        let uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));

        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = synthetic_target_ip;
        let client_port = 51000;
        let target_port = 443;

        let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, client_port, target_port);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            None,
            None,
            None,
        );
        assert!(resetting.is_empty(), "half-open socket must not be reset");
        assert!(sessions.is_empty(), "half-open SYN-RECEIVED sockets must not spawn upstream sessions");

        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        let ack = build_ipv4_tcp_ack_packet(client_ip, target_ip, client_port, target_port, 1, server_seq + 1);
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            None,
            None,
            None,
        );
        assert!(resetting.is_empty(), "pending UID socket must not be reset");
        assert!(sessions.is_empty(), "established sockets must remain parked while UID resolution is pending");

        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_TCP,
            local: SocketAddr::new(IpAddr::V4(client_ip), client_port),
            remote: SocketAddr::new(IpAddr::V4(target_ip), target_port),
        };
        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("TCP flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(10_123));
        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            None,
            None,
            None,
        );
        assert!(resetting.is_empty(), "allowed UID socket must not be reset");
        assert_eq!(sessions.len(), 1, "an authorized resolved UID must open one upstream session");

        let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
        for handle in handles {
            if let Some(entry) = sessions.remove(handle) {
                entry.cancel.cancel();
                entry.handle.abort();
            }
            socket_set.remove(handle);
        }
        ripdpi_flow_app_attribution::clear();
    }

    #[tokio::test]
    async fn denied_uid_tcp_admission_emits_reset_before_socket_removal() {
        let _guard = UID_ADMISSION_TEST_GUARD.lock().expect("UID admission test guard");
        ripdpi_flow_app_attribution::clear();
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
        let mut admission_cursor = 0;
        let mut sessions = ActiveSessions::new(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut dns_cache = None;
        let auth = super::Auth::NoAuth;
        let proxy_sockaddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9);
        let uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));

        let client_ip = Ipv4Addr::new(10, 0, 0, 99);
        let target_ip = Ipv4Addr::new(198, 18, 0, 10);
        let client_port = 51_001;
        let target_port = 443;

        let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, client_port, target_port);
        ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
        let (server_seq, _) = tcp_seq_ack(&syn_ack);
        let ack = build_ipv4_tcp_ack_packet(client_ip, target_ip, client_port, target_port, 1, server_seq + 1);
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            None,
            None,
            None,
        );
        assert!(resetting.is_empty(), "pending UID socket must not be reset");
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_TCP,
            local: SocketAddr::new(IpAddr::V4(client_ip), client_port),
            remote: SocketAddr::new(IpAddr::V4(target_ip), target_port),
        };
        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("TCP flow job");
        ripdpi_flow_app_attribution::store_uid_resolution(&job, Some(20_000));

        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut pending_listens,
            &mut admission_cursor,
            proxy_sockaddr,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut dns_cache,
            None,
            &uid_policy,
            None,
            None,
            None,
        );
        assert_eq!(resetting.len(), 1, "denied established socket must be scheduled for reset cleanup");
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        for handle in resetting {
            socket_set.remove(handle);
        }

        assert!(sessions.is_empty(), "denied UID must never open an upstream session");
        assert!(pending_listens.is_empty(), "denied flow must leave the pending-listen index");
        let mut reset_emitted = false;
        while let Some(packet) = device.tx_queue.pop_front() {
            reset_emitted |= is_injected_rst(&packet);
        }
        assert!(reset_emitted, "denied established TCP flow must emit a RST before its smoltcp socket is removed");
        ripdpi_flow_app_attribution::clear();
    }
}
