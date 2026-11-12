use super::*;

#[test]
fn bulk_request_is_not_delivered_to_a_legacy_helper_before_connecting() {
    let (completed, wait_for_client) = std::sync::mpsc::channel();
    let fixture = TransportFixture::start_listener(move |listener| {
        let (stream, _) = listener.accept().expect("protocol preflight");
        recv_message(&stream, "closed").expect("preflight request");
        send_message(
            &stream,
            &serde_json::to_vec(&current_response(HelperResponse::success(serde_json::Value::Null)))
                .expect("preflight JSON"),
            None,
        )
        .expect("legacy preflight reply");
        wait_for_client.recv_timeout(std::time::Duration::from_secs(3)).expect("client finished");
        listener.set_nonblocking(true).expect("nonblocking listener");
        assert_eq!(listener.accept().expect_err("no bulk command connection").kind(), io::ErrorKind::WouldBlock);
    });
    let request_fd = std::fs::File::open("/dev/null").expect("open request fd");
    let prefix = vec![255u8; 4096];

    let error = fixture
        .transport
        .send_command(
            ripdpi_root_helper_protocol::CMD_SEND_FAKE_TCP,
            serde_json::json!({ "original_prefix": prefix, "fake_prefix": [], "ttl": 1, "default_ttl": 64 }),
            Some(request_fd.as_fd()),
        )
        .expect_err("an oversized frame must not be sent");
    completed.send(()).expect("notify server");
    fixture.join();

    assert!(is_not_delivered(&error));
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    let message = error.to_string();
    assert!(message.contains("did not advertise bulk TCP request limits"), "{message}");
    assert!(message.contains("request is"), "{message}");
}

#[test]
fn advertised_bulk_fake_request_preserves_payloads_and_descriptor() {
    use ripdpi_root_helper_protocol::{
        CMD_SEND_FAKE_TCP, FakeTcpParams, bulk_tcp_preflight_data, recv_request_message,
    };
    use std::io::Read;
    use std::os::unix::net::UnixStream;

    for len in [2048, 16384] {
        let fixture = TransportFixture::start_listener(move |listener| {
            let (preflight, _) = listener.accept().expect("preflight");
            recv_message(&preflight, "closed").expect("preflight request");
            send_message(
                &preflight,
                &serde_json::to_vec(&current_response(HelperResponse::success(bulk_tcp_preflight_data())))
                    .expect("preflight JSON"),
                None,
            )
            .expect("preflight response");
            let (stream, _) = listener.accept().expect("bulk command");
            let (json, fd) = recv_request_message(&stream, "closed").expect("bulk request");
            let request: ripdpi_root_helper_protocol::HelperRequest =
                serde_json::from_slice(&json).expect("request JSON");
            assert_eq!(request.command, CMD_SEND_FAKE_TCP);
            let params: FakeTcpParams = serde_json::from_value(request.params).expect("fake params");
            assert_eq!(params.original_prefix, vec![255; len]);
            assert_eq!(params.fake_prefix, vec![170; len]);
            assert_eq!(params.secondary_fake_prefix, Some(vec![85; len]));
            let mut passed = UnixStream::from(fd.expect("passed descriptor"));
            passed.write_all(b"alive").expect("write passed socket");
            send_message(
                &stream,
                &serde_json::to_vec(&current_response(HelperResponse::success(serde_json::Value::Null)))
                    .expect("response JSON"),
                None,
            )
            .expect("response");
        });
        let (passed, mut peer) = UnixStream::pair().expect("passed socket");
        fixture
            .transport
            .send_command(
                CMD_SEND_FAKE_TCP,
                serde_json::json!({
                    "original_prefix": vec![255_u8; len], "fake_prefix": vec![170_u8; len],
                    "secondary_fake_prefix": vec![85_u8; len], "ttl": 8, "default_ttl": 64,
                }),
                Some(passed.as_fd()),
            )
            .expect("bulk fake request");
        let mut actual = [0; 5];
        peer.read_exact(&mut actual).expect("read passed socket");
        assert_eq!(&actual, b"alive");
        fixture.join();
    }
}
