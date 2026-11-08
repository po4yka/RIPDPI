use super::*;

#[test]
fn socks4_reader_preserves_application_bytes_after_exact_request_boundary() {
    for (ip, domain, expected_addr) in [
        ([0, 0, 0, 0], None, [0, 0, 0, 0]),
        ([0, 0, 0, 1], Some("example.com"), [198, 51, 100, 10]),
        ([0, 0, 0, 255], Some("example.com"), [198, 51, 100, 10]),
        ([0, 0, 1, 0], None, [0, 0, 1, 0]),
    ] {
        let mut request = vec![4, 1, 0, 80];
        request.extend_from_slice(&ip);
        request.extend_from_slice(b"user\0");
        if let Some(domain) = domain {
            request.extend_from_slice(domain.as_bytes());
            request.push(0);
        }
        let (mut reader, mut writer) = connected_pair();
        reader.set_read_timeout(Some(Duration::from_secs(1))).expect("bound payload read");
        // The dispatcher has consumed the version byte.
        writer.write_all(&request[1..]).expect("send request tail");
        writer.write_all(b"application\0bytes").expect("send application payload");
        let consumed = read_socks4_request(&mut reader, 4, test_deadline()).expect("read request");
        assert_eq!(consumed, request);
        let mut payload = [0; 17];
        reader.read_exact(&mut payload).expect("read preserved payload");
        assert_eq!(&payload, b"application\0bytes");

        let state = runtime_state(RuntimeConfig::default());
        let RuntimeClientRequest::Socks4Connect(target) = state
            .parse_socks4_client_request(&consumed, |_| Some(SocketAddr::from(([198, 51, 100, 10], 0))))
            .expect("parse complete request")
        else {
            panic!("expected SOCKS4 CONNECT");
        };
        assert_eq!(target.addr, SocketAddr::from((expected_addr, 80)));
        assert_eq!(target.host.as_deref(), domain);
    }
}
