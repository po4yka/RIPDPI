use super::*;
use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;

#[test]
fn upstream_connect_failures_keep_their_validated_wire_reply() {
    let mut cases: Vec<([u8; 4], u8)> = (1..=8).map(|code| ([5, code, 0, 1], code)).collect();
    cases.extend([([4, 3, 0, 1], 1), ([5, 3, 1, 1], 1), ([5, 9, 0, 1], 1), ([5, 0, 1, 1], 1), ([4, 0, 0, 1], 1)]);
    for (header, expected_code) in cases {
        let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
        let upstream_addr = upstream.local_addr().expect("upstream address");
        let peer = thread::spawn(move || {
            let (mut stream, _) = upstream.accept().expect("accept upstream");
            stream.set_read_timeout(Some(Duration::from_secs(2))).expect("bound upstream read");
            let mut greeting = [0; 3];
            stream.read_exact(&mut greeting).expect("read method request");
            assert_eq!(greeting, [5, 1, 0]);
            stream.write_all(&[5, 0]).expect("select no auth");
            let mut request = [0; 10];
            stream.read_exact(&mut request).expect("read CONNECT request");
            assert_eq!(request, [5, 1, 0, 1, 127, 0, 0, 1, 0, 9]);
            let mut reply = header.to_vec();
            reply.extend_from_slice(&[127, 0, 0, 1, 0x30, 0x39]);
            stream.write_all(&reply).expect("write independently encoded reply");
        });
        let mut config = RuntimeConfig::default();
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: upstream_addr, auth: None });
        let state = runtime_state(config);
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound client read");
        let handler = thread::spawn(move || super::super::handle_client(server, &state));
        client.write_all(&[5, 1, 0]).expect("write method request");
        let mut method = [0; 2];
        client.read_exact(&mut method).expect("read method selection");
        assert_eq!(method, [5, 0]);
        client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 9]).expect("write CONNECT request");
        let mut reply = [0; 10];
        client.read_exact(&mut reply).expect("read CONNECT failure");
        assert_eq!(reply, [5, expected_code, 0, 1, 0, 0, 0, 0, 0, 0], "upstream header {header:?}");
        assert_eq!(client.read(&mut [0; 1]).expect("read terminal EOF"), 0);
        assert!(handler.join().expect("join handler").is_err());
        peer.join().expect("join upstream");
    }
}
