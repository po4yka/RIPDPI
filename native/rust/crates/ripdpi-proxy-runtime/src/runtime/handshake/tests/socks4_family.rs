use super::*;
use std::net::Ipv6Addr;

#[test]
fn socks4a_disabled_ipv6_rejects_literal_without_origin_connection() {
    let origin = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).expect("bind IPv6 origin");
    origin.set_nonblocking(true).expect("nonblocking origin");
    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.ipv6 = false;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound client read");
    let worker = thread::spawn(move || super::super::handle_client(server, &state));

    let mut request = vec![4, 1];
    request.extend_from_slice(&origin.local_addr().expect("origin address").port().to_be_bytes());
    request.extend_from_slice(&[0, 0, 0, 1, 0]);
    request.extend_from_slice(b"::1\0");
    client.write_all(&request).expect("send SOCKS4a request");
    let mut reply = [0; 8];
    client.read_exact(&mut reply).expect("read SOCKS4 result");
    drop(client);
    worker.join().expect("join handler").expect("complete handler");

    assert_eq!(reply, [0, 0x5b, 0, 0, 0, 0, 0, 0]);
    assert_eq!(origin.accept().expect_err("no IPv6 origin connection").kind(), ErrorKind::WouldBlock);
}
