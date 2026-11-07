use super::*;

#[test]
fn absolute_domain_connect_reaches_local_origin_through_local_dns() {
    let stack =
        FixtureStack::start(FixtureConfig { dns_answer_ipv4: "127.0.0.1".to_string(), ..dynamic_fixture_config() })
            .expect("start local fixture");
    for host in ["a.", "abc.test."] {
        let context = fixture_runtime_context(stack.manifest().dns_http_port);
        let state = runtime_state_with_context(RuntimeConfig::default(), Some(context));
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(3))).expect("bound client read");
        let worker = thread::spawn(move || super::super::handle_client(server, &state));
        client.write_all(&[5, 1, 0]).expect("send negotiation");
        let mut negotiation = [0; 2];
        client.read_exact(&mut negotiation).expect("read negotiation");
        assert_eq!(negotiation, [5, 0]);
        let mut request = vec![5, 1, 0, 3, host.len() as u8];
        request.extend_from_slice(host.as_bytes());
        request.extend_from_slice(&stack.manifest().tcp_echo_port.to_be_bytes());
        client.write_all(&request).expect("send absolute domain request");
        let mut reply = [0; 10];
        client.read_exact(&mut reply).expect("read CONNECT result");
        assert_eq!(&reply[..4], &[5, 0, 0, 1], "{host} must reach the local origin");
        client.write_all(b"absolute-fqdn").expect("send payload");
        let mut echoed = [0; 13];
        client.read_exact(&mut echoed).expect("read payload");
        assert_eq!(&echoed, b"absolute-fqdn");
        client.shutdown(Shutdown::Write).expect("send FIN");
        drop(client);
        worker.join().expect("join handler").expect("complete handler");
    }
}
