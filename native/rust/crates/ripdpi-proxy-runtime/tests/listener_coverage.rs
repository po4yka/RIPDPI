#![cfg(not(feature = "loom"))]

mod support;

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::{Mutex, PoisonError};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use support::proxy::start_proxy_with_listener;
use support::socks5::recv_socks5_reply;

static TEST_LOCK: Mutex<()> = Mutex::new(());
const REQUEST: &[u8] = b"\x00\xff raw request\r\n";
const RESPONSE: &[u8] = b"distinct local service\x00\xff";

#[test]
fn exact_prebound_listener_allows_separate_other_family_service_on_same_port() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for (proxy_ip, origin_ip) in [
        (IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)),
        (IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V4(Ipv4Addr::LOCALHOST)),
    ] {
        let listener = listener(proxy_ip, true);
        let port = listener.local_addr().expect("proxy address").port();
        let origin = TcpListener::bind((origin_ip, port)).expect("bind separate origin on same port");
        exchange(listener, proxy_ip, origin, false, 0, true);
    }
}

#[test]
fn exact_ipv4_listener_allows_unspecified_ipv6_target_to_separate_same_port_service() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let listener = listener(Ipv4Addr::LOCALHOST.into(), false);
    let port = listener.local_addr().expect("proxy address").port();
    let origin = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP)).expect("create IPv6 origin");
    origin.set_only_v6(true).expect("separate IPv6-only origin");
    origin.bind(&SockAddr::from(SocketAddr::from((Ipv6Addr::LOCALHOST, port)))).expect("bind same-port IPv6 origin");
    origin.listen(1).expect("listen for IPv6 origin client");
    let origin: TcpListener = origin.into();
    let target = SocketAddr::from((Ipv6Addr::UNSPECIFIED, port));

    // The kernel, rather than a listener-family model, establishes the target's destination.
    let mut direct = TcpStream::connect(target).expect("kernel connects unspecified IPv6 to the separate origin");
    direct.set_read_timeout(Some(Duration::from_secs(3))).expect("bound oracle reads");
    let (mut accepted, _) = origin.accept().expect("accept direct kernel oracle");
    accepted.set_nonblocking(false).expect("blocking oracle stream");
    accepted.set_read_timeout(Some(Duration::from_secs(3))).expect("bound accepted oracle reads");
    assert_eq!(accepted.local_addr().expect("oracle origin address"), origin.local_addr().expect("origin address"));
    direct.write_all(REQUEST).expect("write direct oracle payload");
    let mut request = vec![0; REQUEST.len()];
    accepted.read_exact(&mut request).expect("read direct oracle payload");
    assert_eq!(request, REQUEST);
    accepted.write_all(RESPONSE).expect("write direct oracle response");
    let mut response = vec![0; RESPONSE.len()];
    direct.read_exact(&mut response).expect("read direct oracle response");
    assert_eq!(response, RESPONSE);
    drop(accepted);
    drop(direct);

    exchange_to(listener, Ipv4Addr::LOCALHOST.into(), origin, target, false, 0, true);
}

#[cfg(target_os = "linux")]
#[test]
fn exact_ipv4_listener_allows_another_loopback_address_with_same_port() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let listener = listener(Ipv4Addr::LOCALHOST.into(), false);
    let port = listener.local_addr().expect("proxy address").port();
    let origin = TcpListener::bind((Ipv4Addr::new(127, 0, 0, 2), port)).expect("bind separate loopback origin");
    exchange(listener, Ipv4Addr::LOCALHOST.into(), origin, false, 0, true);
}

#[cfg(target_os = "linux")]
#[test]
fn mapped_wildcard_rejects_ipv4_alias_and_allows_separate_ipv6_service() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let mapped = IpAddr::V6(Ipv4Addr::UNSPECIFIED.to_ipv6_mapped());
    let mapped_listener = listener(mapped, false);
    let port = mapped_listener.local_addr().expect("mapped wildcard address").port();
    let proxy = start_proxy_with_listener(config(0), mapped_listener, None);
    let mut client = authenticate(SocketAddr::from((Ipv4Addr::LOCALHOST, port)));
    request(&mut client, SocketAddr::from((Ipv4Addr::new(127, 0, 0, 2), port)), false);
    let reply = recv_socks5_reply(&mut client);
    drop(client);
    drop(proxy);
    assert_eq!(reply[1], 2, "mapped wildcard covers the other IPv4 loopback alias");

    let mapped_listener = listener(mapped, false);
    let port = mapped_listener.local_addr().expect("mapped wildcard address").port();
    let origin = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP)).expect("create IPv6 origin");
    origin.set_only_v6(true).expect("separate IPv6-only origin");
    origin.bind(&SockAddr::from(SocketAddr::from((Ipv6Addr::LOCALHOST, port)))).expect("bind same-port IPv6 origin");
    origin.listen(1).expect("listen for IPv6 origin client");
    exchange(mapped_listener, Ipv4Addr::LOCALHOST.into(), origin.into(), false, 0, true);
}

#[test]
fn ipv6_only_wildcard_allows_separate_ipv4_service_on_same_port() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let listener = listener(Ipv6Addr::UNSPECIFIED.into(), true);
    let port = listener.local_addr().expect("proxy address").port();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, port)).expect("bind separate IPv4 origin");
    exchange(listener, Ipv6Addr::LOCALHOST.into(), origin, false, 0, true);
}

#[test]
fn prebound_wildcard_listener_rejects_covered_self_targets() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for (bind_ip, connect_ip, target_ip, only_v6) in [
        (
            Ipv4Addr::LOCALHOST.into(),
            Ipv4Addr::LOCALHOST.into(),
            IpAddr::V6(Ipv4Addr::UNSPECIFIED.to_ipv6_mapped()),
            false,
        ),
        (
            Ipv4Addr::LOCALHOST.into(),
            Ipv4Addr::LOCALHOST.into(),
            IpAddr::V6(Ipv4Addr::LOCALHOST.to_ipv6_mapped()),
            false,
        ),
        (Ipv4Addr::UNSPECIFIED.into(), Ipv4Addr::LOCALHOST.into(), Ipv4Addr::LOCALHOST.into(), false),
        (Ipv4Addr::UNSPECIFIED.into(), Ipv4Addr::LOCALHOST.into(), Ipv4Addr::UNSPECIFIED.into(), false),
        (Ipv6Addr::UNSPECIFIED.into(), Ipv4Addr::LOCALHOST.into(), Ipv6Addr::LOCALHOST.into(), false),
        (Ipv6Addr::UNSPECIFIED.into(), Ipv6Addr::LOCALHOST.into(), Ipv4Addr::LOCALHOST.into(), false),
    ] {
        let listener = listener(bind_ip, only_v6);
        let port = listener.local_addr().expect("proxy address").port();
        let proxy = start_proxy_with_listener(config(0), listener, None);
        let mut client = authenticate(SocketAddr::new(connect_ip, port));
        request(&mut client, SocketAddr::new(target_ip, port), false);
        let reply = recv_socks5_reply(&mut client);
        drop(client);
        drop(proxy);
        assert_eq!(reply[1], 2, "actual wildcard coverage must reject {target_ip}:{port}");
    }
}

#[test]
fn private_resolved_targets_keep_the_existing_mapped_peer_rejection() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let mapped = IpAddr::V6(Ipv4Addr::LOCALHOST.to_ipv6_mapped());
    let listener = listener(mapped, false);
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind private target origin");
    exchange(listener, mapped, origin, true, 0, false);
}

#[test]
fn private_resolved_targets_require_actual_loopback_and_requested_ephemeral_port() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for (bind_ip, requested_port, allowed) in [
        (IpAddr::V4(Ipv4Addr::LOCALHOST), 0, true),
        (IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0, false),
        (IpAddr::V4(Ipv4Addr::LOCALHOST), 1080, false),
    ] {
        let listener = listener(bind_ip, false);
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind private target origin");
        exchange(listener, Ipv4Addr::LOCALHOST.into(), origin, true, requested_port, allowed);
    }
}

fn listener(ip: IpAddr, only_v6: bool) -> TcpListener {
    let domain = if ip.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).expect("create listener");
    if ip.is_ipv6() {
        socket.set_only_v6(only_v6).expect("set actual IPv6 coverage");
    }
    socket.bind(&SockAddr::from(SocketAddr::new(ip, 0))).expect("bind listener");
    socket.listen(8).expect("listen");
    socket.into()
}

fn config(requested_port: u16) -> RuntimeConfig {
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = requested_port;
    config.network.listen.auth_token = Some("secret".to_string());
    config
}

fn authenticate(proxy: SocketAddr) -> TcpStream {
    let mut client = TcpStream::connect(proxy).expect("connect proxy");
    client.set_read_timeout(Some(Duration::from_secs(3))).expect("bound client reads");
    client.set_write_timeout(Some(Duration::from_secs(3))).expect("bound client writes");
    client.write_all(b"\x05\x01\x02\x01\x07attempt\x06secret").expect("write literal SOCKS5 authentication");
    let mut reply = [0; 4];
    client.read_exact(&mut reply).expect("read authentication reply");
    assert_eq!(reply, [5, 2, 1, 0]);
    client
}

fn request(client: &mut TcpStream, target: SocketAddr, private: bool) {
    let mut bytes = vec![5, 1, 0];
    if private {
        bytes.extend_from_slice(&[5, 1, b'x']);
    }
    match target.ip() {
        IpAddr::V4(ip) => {
            bytes.push(1);
            bytes.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            bytes.push(4);
            bytes.extend_from_slice(&ip.octets());
        }
    }
    bytes.extend_from_slice(&target.port().to_be_bytes());
    client.write_all(&bytes).expect("write literal CONNECT destination");
}

fn exchange(
    listener: TcpListener,
    connect_ip: IpAddr,
    origin: TcpListener,
    private: bool,
    requested_port: u16,
    allowed: bool,
) {
    let target = origin.local_addr().expect("origin address");
    exchange_to(listener, connect_ip, origin, target, private, requested_port, allowed);
}

fn exchange_to(
    listener: TcpListener,
    connect_ip: IpAddr,
    origin: TcpListener,
    target: SocketAddr,
    private: bool,
    requested_port: u16,
    allowed: bool,
) {
    origin.set_nonblocking(true).expect("nonblocking origin");
    let origin_worker = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match origin.accept() {
                Ok((mut stream, _)) => {
                    // Darwin inherits the listener's nonblocking mode on accept.
                    stream.set_nonblocking(false).expect("blocking origin stream");
                    stream.set_read_timeout(Some(Duration::from_secs(3))).expect("bound origin read");
                    let mut request = vec![0; REQUEST.len()];
                    stream.read_exact(&mut request).expect("read raw payload");
                    assert_eq!(request, REQUEST);
                    stream.write_all(RESPONSE).expect("write raw origin response");
                    return true;
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return false,
                Err(error) => panic!("accept origin: {error}"),
            }
        }
    });
    let port = listener.local_addr().expect("proxy address").port();
    let proxy = start_proxy_with_listener(config(requested_port), listener, None);
    let mut client = authenticate(SocketAddr::new(connect_ip, port));
    request(&mut client, target, private);
    let reply = if private && client.peek(&mut [0]).expect("peek private admission result") == 0 {
        vec![]
    } else {
        recv_socks5_reply(&mut client)
    };
    let mut response = vec![];
    if reply.get(1) == Some(&0) {
        client.write_all(REQUEST).expect("write raw payload");
        client.shutdown(Shutdown::Write).expect("finish client request");
        client.read_to_end(&mut response).expect("read raw origin response");
    }
    drop(client);
    drop(proxy);
    let connected = origin_worker.join().expect("join origin");
    if allowed {
        assert_eq!(reply.get(1), Some(&0), "unexpected admission for {target}");
    } else {
        assert!(reply.is_empty(), "private address rejection closes without a protocol reply");
    }
    assert_eq!(connected, allowed, "origin accept must follow admission");
    assert_eq!(response, if allowed { RESPONSE } else { b"" });
}
