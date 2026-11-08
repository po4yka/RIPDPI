use super::*;
use crate::runtime::config::RuntimeConfig;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, TcpListener};
use std::thread;
use std::time::{Duration, Instant};

const REQUEST: &[u8] = b"\x00\xff\x05CONNECT\x04\r\n";
const RESPONSE: &[u8] = b"transparent\x00\xff response";

#[test]
fn transparent_connect_respects_destination_family_policy() {
    for (origin_ip, mapped, ipv6_enabled, allowed) in [
        (IpAddr::V4(Ipv4Addr::LOCALHOST), false, false, true),
        (IpAddr::V4(Ipv4Addr::LOCALHOST), true, false, true),
        (IpAddr::V6(Ipv6Addr::LOCALHOST), false, false, false),
        (IpAddr::V6(Ipv6Addr::LOCALHOST), false, true, true),
    ] {
        raw_exchange(origin_ip, mapped, ipv6_enabled, allowed);
    }
}

fn raw_exchange(origin_ip: IpAddr, mapped: bool, ipv6_enabled: bool, allowed: bool) {
    let client_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client listener");
    let mut peer = TcpStream::connect(client_listener.local_addr().expect("client address")).expect("connect peer");
    let (mut client, _) = client_listener.accept().expect("accept peer");
    let origin = loop {
        let origin = TcpListener::bind((origin_ip, 0)).expect("bind origin");
        if origin.local_addr().expect("origin address").port() != client.local_addr().expect("client address").port() {
            break origin;
        }
    };
    let mut target = origin.local_addr().expect("origin address");
    if mapped {
        target = SocketAddr::new(IpAddr::V6(Ipv4Addr::LOCALHOST.to_ipv6_mapped()), target.port());
    }
    origin.set_nonblocking(true).expect("nonblocking origin");
    let origin_worker = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match origin.accept() {
                Ok((mut stream, _)) => {
                    stream.set_read_timeout(Some(Duration::from_secs(2))).expect("origin read timeout");
                    let mut request = vec![0; REQUEST.len()];
                    stream.read_exact(&mut request).expect("read raw request");
                    assert_eq!(request, REQUEST, "the original bytes must reach the origin");
                    stream.write_all(RESPONSE).expect("write raw response");
                    stream.shutdown(Shutdown::Write).expect("finish origin response");
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
    let mut config = RuntimeConfig::default();
    config.network.transparent = true;
    config.network.ipv6 = ipv6_enabled;
    crate::runtime::validate_proxy_config(&config).expect("valid Transparent configuration");
    let state = RuntimeState::test(config);
    peer.set_read_timeout(Some(Duration::from_secs(3))).expect("peer read timeout");
    peer.write_all(REQUEST).expect("write raw request");
    peer.shutdown(Shutdown::Write).expect("finish peer request");

    // Destination admission starts after the operating system recovers the original target.
    let result = connect_and_relay(&mut client, target, &state, None, None, SuccessReply::None);
    drop(client);
    let mut response = Vec::new();
    match peer.read_to_end(&mut response) {
        Ok(_) => {}
        Err(error) if !allowed && error.kind() == io::ErrorKind::ConnectionReset => {}
        Err(error) => panic!("read raw response: {error}"),
    }
    let connected = origin_worker.join().expect("join origin");
    if allowed {
        result.expect("allowed destination relays");
        assert!(connected, "allowed target {target} must receive a connection");
        assert_eq!(response, RESPONSE, "Transparent must not add a proxy reply");
    } else {
        assert_eq!(result.expect_err("disabled IPv6 must be denied").kind(), io::ErrorKind::PermissionDenied);
        assert!(!connected, "disabled IPv6 must not open an origin connection");
        assert!(response.is_empty(), "denied Transparent must not send a proxy reply");
    }
}
