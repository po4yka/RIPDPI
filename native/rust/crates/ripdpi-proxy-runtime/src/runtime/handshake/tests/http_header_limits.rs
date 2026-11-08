use super::*;

fn connect_header(authority: SocketAddr, size: usize) -> Vec<u8> {
    let prefix = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nX-Pad: ");
    let mut request = prefix.into_bytes();
    request.resize(size - 4, b'a');
    request.extend_from_slice(b"\r\n\r\n");
    assert_eq!(request.len(), size);
    request
}

fn read_header(stream: &mut TcpStream) -> Vec<u8> {
    let mut reply = Vec::new();
    loop {
        let mut byte = [0];
        match stream.read(&mut byte) {
            Ok(0) => return reply,
            Ok(_) => reply.push(byte[0]),
            Err(error) if error.kind() == ErrorKind::ConnectionReset => return reply,
            Err(error) => panic!("read HTTP response: {error}"),
        }
        if reply.ends_with(b"\r\n\r\n") {
            return reply;
        }
    }
}

#[test]
fn excessive_http_connect_header_gets_431_before_upstream_connect() {
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
    upstream.set_nonblocking(true).expect("bound accept");
    let request = connect_header(upstream.local_addr().expect("upstream address"), 65_537);
    let mut config = RuntimeConfig::default();
    config.network.http_connect = true;
    let state = runtime_state(config);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(3))).expect("bound client read");
    let handler = thread::spawn(move || super::super::handle_client(server, &state));
    client.write_all(&request).expect("send excessive request");
    let reply = read_header(&mut client);
    assert_eq!(
        reply,
        b"HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
    );
    let error = handler.join().expect("join handler").expect_err("preserve reader error classification");
    assert_eq!(error.kind(), ErrorKind::InvalidData);
    match client.read(&mut [0]) {
        Ok(0) => {}
        Err(error) if error.kind() == ErrorKind::ConnectionReset => {}
        result => panic!("oversized request must close after its HTTP error: {result:?}"),
    }
    assert_eq!(upstream.accept().expect_err("no upstream dial").kind(), ErrorKind::WouldBlock);
}

#[test]
fn legal_http_connect_header_at_limit_keeps_early_tunnel_bytes() {
    const PAYLOAD: &[u8] = b"header-boundary\x00\xff";
    for size in [65_535, 65_536] {
        let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
        let mut request = connect_header(upstream.local_addr().expect("upstream address"), size);
        request.extend_from_slice(PAYLOAD);
        let peer = thread::spawn(move || {
            let (mut stream, _) = upstream.accept().expect("accept upstream");
            stream.set_read_timeout(Some(Duration::from_secs(3))).expect("bound upstream read");
            let mut body = [0; PAYLOAD.len()];
            stream.read_exact(&mut body).expect("read tunnel payload");
            assert_eq!(body, PAYLOAD);
            stream.write_all(&body).expect("echo payload");
        });
        let mut config = RuntimeConfig::default();
        config.network.http_connect = true;
        let state = runtime_state(config);
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(3))).expect("bound client read");
        let handler = thread::spawn(move || super::super::handle_client(server, &state));
        client.write_all(&request).expect("send request and early payload");
        assert_eq!(read_header(&mut client), b"HTTP/1.1 200 OK\r\n\r\n");
        let mut body = [0; PAYLOAD.len()];
        client.read_exact(&mut body).expect("read tunnel response");
        assert_eq!(body, PAYLOAD);
        drop(client);
        peer.join().expect("join upstream");
        handler.join().expect("join handler").expect("tunnel completes");
    }
}
