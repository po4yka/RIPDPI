use super::*;

fn request(userid_len: usize, domain: Option<&[u8]>, port: u16) -> Vec<u8> {
    let mut request = vec![4, 1];
    request.extend_from_slice(&port.to_be_bytes());
    request.extend_from_slice(if domain.is_some() { &[0, 0, 0, 1] } else { &[127, 0, 0, 1] });
    request.extend(vec![b'u'; userid_len]);
    request.push(0);
    if let Some(domain) = domain {
        request.extend_from_slice(domain);
        request.push(0);
    }
    request
}

fn read_closed_reply(client: &mut TcpStream) -> Vec<u8> {
    let mut reply = Vec::new();
    let result = client.read_to_end(&mut reply);
    assert!(result.is_ok() || result.is_err_and(|error| error.kind() == ErrorKind::ConnectionReset));
    reply
}

#[test]
fn socks4_oversized_userid_and_domain_requests_receive_one_failure_reply() {
    for (userid_len, domain) in [(4089, None), (4079, Some(b"localhost".as_slice()))] {
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(1))).expect("bound rejection read");
        let request = request(userid_len, domain, 80);
        for chunk in request[1..].chunks(17) {
            client.write_all(chunk).expect("send request fragments");
        }
        let state = runtime_state(RuntimeConfig::default());
        let error = super::super::handle_socks4(server, &state, 4, test_deadline()).expect_err("reject size");
        assert_eq!(error.kind(), ErrorKind::InvalidData);
        assert_eq!(read_closed_reply(&mut client), [0, 0x5b, 0, 0, 0, 0, 0, 0]);
    }
}

#[test]
fn socks4_largest_numeric_userid_preserves_payload_round_trip() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    let port = origin.local_addr().expect("origin address").port();
    let origin_worker = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept origin connection");
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("bound origin read");
        let mut payload = [0; 4];
        stream.read_exact(&mut payload).expect("read origin payload");
        assert_eq!(&payload, b"ping");
        stream.write_all(b"pong").expect("write origin response");
    });
    let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound client read");
    let proxy_worker = thread::spawn(move || super::super::handle_client(server, &state));
    let mut request = request(4088, None, port);
    request.extend_from_slice(b"ping");
    client.write_all(&request).expect("send maximum USERID and payload");
    let mut reply_and_payload = [0; 12];
    client.read_exact(&mut reply_and_payload).expect("read CONNECT success and response");
    assert_eq!(&reply_and_payload, b"\x00\x5a\0\0\0\0\0\0pong");
    drop(client);
    origin_worker.join().expect("join origin");
    proxy_worker.join().expect("join proxy").expect("complete flow");
}

#[test]
fn socks4_domain_and_userid_share_the_existing_request_budget() {
    for (userid_len, domain_len, accepted) in [(4084, 3, true), (4085, 3, false), (3832, 255, true), (3833, 255, false)]
    {
        let domain = vec![b'a'; domain_len];
        let request = request(userid_len, Some(&domain), 80);
        let (mut reader, mut writer) = connected_pair();
        writer.write_all(&request[1..]).expect("send request tail");
        let result = read_socks4_request(&mut reader, 4, test_deadline());
        if accepted {
            assert_eq!(result.expect("accept boundary request"), request);
        } else {
            assert_eq!(result.expect_err("reject next byte").kind(), ErrorKind::InvalidData);
        }
    }
}

#[test]
fn socks4_truncated_fields_fail_without_a_size_rejection_reply() {
    for tail in [b"\x01\x00".as_slice(), b"\x01\x00\x50\x7f\0\0\x01u", b"\x01\x00\x50\0\0\0\x01\0abc"] {
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(1))).expect("bound closure read");
        client.write_all(tail).expect("send incomplete request");
        client.shutdown(Shutdown::Write).expect("send EOF");
        let state = runtime_state(RuntimeConfig::default());
        let error = super::super::handle_socks4(server, &state, 4, test_deadline()).expect_err("incomplete request");
        assert_eq!(error.kind(), ErrorKind::UnexpectedEof);
        assert!(read_closed_reply(&mut client).is_empty());
    }
}

#[test]
fn socks4_unterminated_field_keeps_the_handshake_deadline() {
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("bound closure read");
    client.write_all(b"\x01\x00\x50\x7f\0\0\x01u").expect("send unterminated USERID");
    let state = runtime_state(RuntimeConfig::default());
    let error = super::super::handle_socks4(server, &state, 4, Instant::now() + Duration::from_millis(50))
        .expect_err("deadline expires");
    assert_eq!(error.kind(), ErrorKind::TimedOut);
    assert!(read_closed_reply(&mut client).is_empty());
}
