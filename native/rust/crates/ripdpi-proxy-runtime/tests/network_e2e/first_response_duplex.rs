use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use super::support::proxy::{ephemeral_proxy_config, start_proxy};
use super::support::socks5::socks_connect;
use ring::digest::{SHA256, digest};

fn read_headers(stream: &mut TcpStream) -> Vec<u8> {
    let mut headers = Vec::new();
    while !headers.ends_with(b"\r\n\r\n") {
        let mut byte = [0];
        stream.read_exact(&mut byte).expect("read bounded headers");
        headers.push(byte[0]);
        assert!(headers.len() <= 4096);
    }
    headers
}

#[test]
fn autolearn_forwards_split_header_upload_before_first_response() {
    let _guard = super::test_guard();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind owned origin");
    let target = origin.local_addr().expect("origin address");
    let payload: Vec<u8> = (0..1024 * 1024).map(|index| (index % 251) as u8).collect();
    let expected_digest = digest(&SHA256, &payload);
    let (headers_sent, headers_received) = mpsc::sync_channel(1);
    let server = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept origin connection");
        stream.set_read_timeout(Some(Duration::from_secs(3))).expect("bound origin read");
        stream.set_write_timeout(Some(Duration::from_secs(3))).expect("bound origin write");
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            let mut byte = [0];
            stream.read_exact(&mut byte).expect("read request headers");
            headers.extend_from_slice(&byte);
            assert!(headers.len() <= 4096);
        }
        headers_sent.send(()).expect("report headers received");
        let mut body = vec![0u8; 1024 * 1024];
        let read = stream.read_exact(&mut body);
        if read.is_ok() {
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK").expect("write response");
        }
        let _ = stream.shutdown(Shutdown::Both);
        read.expect("the complete POST body must arrive before the response");
        digest(&SHA256, &body)
    });
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    let proxy = start_proxy(config, None);
    let mut client = socks_connect(proxy.port, target.port());
    client.set_read_timeout(Some(Duration::from_secs(4))).expect("bound client read");
    client.set_write_timeout(Some(Duration::from_secs(4))).expect("bound client write");
    client
        .write_all(b"POST /upload HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 1048576\r\n\r\n")
        .expect("send headers separately");
    headers_received.recv_timeout(Duration::from_secs(2)).expect("origin has the headers");
    let sent = client.write_all(&payload);
    let mut response = Vec::new();
    let received = client.read_to_end(&mut response);
    let _ = client.shutdown(Shutdown::Both);
    drop(proxy);
    let actual_digest = server.join().expect("join owned origin");
    sent.expect("upload body");
    received.expect("read response");
    assert_eq!(actual_digest.as_ref(), expected_digest.as_ref());
    assert_eq!(response, b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
}

#[test]
fn early_response_reaches_backpressured_upload_and_owner_stop_joins_workers() {
    use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;
    use std::sync::atomic::Ordering;
    let _guard = super::test_guard();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    let port = origin.local_addr().expect("origin address").port();
    let (headers_tx, headers_rx) = mpsc::sync_channel(1);
    let (release_tx, release_rx) = mpsc::sync_channel(1);
    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
    let server = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept origin");
        stream.set_read_timeout(Some(Duration::from_secs(4))).expect("bound origin read");
        socket2::SockRef::from(&stream).set_recv_buffer_size(4096).expect("limit origin receive window");
        read_headers(&mut stream);
        headers_tx.send(()).expect("headers received");
        stream.read_exact(&mut [0]).expect("continuation has reached origin");
        stream.write_all(response).expect("send early response");
        stream.shutdown(Shutdown::Write).expect("origin half-closes its response");
        // Keep the read half open without draining the rest of the upload.
        release_rx.recv_timeout(Duration::from_secs(8)).expect("owner stopped blocked upload");
    });
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    let telemetry = Arc::new(super::RecordingTelemetry::default());
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));
    let mut client = socks_connect(proxy.port, port);
    client.set_read_timeout(Some(Duration::from_secs(4))).expect("bound response read");
    socket2::SockRef::from(&client).set_send_buffer_size(4096).expect("bound client send window");
    client
        .write_all(b"POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 16777216\r\n\r\n")
        .expect("write separate headers");
    headers_rx.recv_timeout(Duration::from_secs(2)).expect("headers reached origin");
    let mut writer = client.try_clone().expect("independent upload writer");
    let (sent_tx, sent_rx) = mpsc::sync_channel(1);
    let upload = thread::spawn(move || {
        let result = writer.write_all(&vec![0x5a; 16 * 1024 * 1024]);
        sent_tx.send(result).expect("report upload completion");
    });
    let mut received = Vec::new();
    let response_result = client.read_to_end(&mut received);
    let blocked = sent_rx.try_recv().is_err();
    let stopped = std::time::Instant::now();
    proxy.control.request_shutdown();
    drop(proxy);
    let stop_elapsed = stopped.elapsed();
    let finished_before_fixture_cleanup = telemetry.finished.load(Ordering::Relaxed);
    // The proxy owns its workers, while this test owns the external uploader.
    // A remote FIN does not cancel a blocked write on the external socket.
    let _ = client.shutdown(Shutdown::Both);
    let uploaded = sent_rx.recv_timeout(Duration::from_secs(2));
    release_tx.send(()).expect("release owned origin");
    server.join().expect("join origin");
    upload.join().expect("join uploader");
    response_result.expect("early response while upload is blocked");
    assert_eq!(received, response);
    assert!(blocked, "large upload must still be backpressured when the response arrives");
    assert!(uploaded.expect("test client shutdown releases its uploader").is_err());
    assert_eq!(finished_before_fixture_cleanup, 1, "relay worker and inbound join must finish before fixture cleanup");
    assert!(stop_elapsed < Duration::from_secs(5), "owner stop took {stop_elapsed:?}");
}

#[test]
fn client_half_close_preserves_the_response_after_continued_body() {
    let _guard = super::test_guard();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    let port = origin.local_addr().expect("origin address").port();
    let (headers_tx, headers_rx) = mpsc::sync_channel(1);
    let server = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept origin");
        stream.set_read_timeout(Some(Duration::from_secs(4))).expect("bound origin read");
        read_headers(&mut stream);
        headers_tx.send(()).expect("report headers");
        let mut body = Vec::new();
        stream.read_to_end(&mut body).expect("client FIN follows the complete body");
        assert_eq!(body, vec![0x39; 64 * 1024]);
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK").expect("reply after FIN");
    });
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    let proxy = start_proxy(config, None);
    let mut client = socks_connect(proxy.port, port);
    client.write_all(b"POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 65536\r\n\r\n").expect("send headers");
    headers_rx.recv_timeout(Duration::from_secs(2)).expect("origin has headers");
    client.write_all(&vec![0x39; 64 * 1024]).expect("send continued body");
    client.shutdown(Shutdown::Write).expect("half-close only upload");
    let mut response = Vec::new();
    let received = client.read_to_end(&mut response);
    let _ = client.shutdown(Shutdown::Both);
    drop(proxy);
    server.join().expect("join origin");
    received.expect("read response after half-close");
    assert_eq!(response, b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
}

#[test]
fn classified_response_after_continuation_is_not_replayed_or_learned_as_success() {
    use ripdpi_proxy_runtime_adapter::model::config::{DETECT_HTTP_BLOCKPAGE, DesyncGroup, UpstreamSocksConfig};
    use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

    let _guard = super::test_guard();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    let port = origin.local_addr().expect("origin address").port();
    let alternate = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind alternate upstream");
    alternate.set_nonblocking(true).expect("observe alternate without waiting");
    let response = b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n";
    let (headers_tx, headers_rx) = mpsc::sync_channel(1);
    let server = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept primary origin");
        stream.set_read_timeout(Some(Duration::from_secs(4))).expect("bound origin read");
        read_headers(&mut stream);
        headers_tx.send(()).expect("headers received");
        let mut body = [0; 8192];
        stream.read_exact(&mut body).expect("continued bytes reached the origin");
        assert_eq!(body, [0x53; 8192]);
        stream.write_all(response).expect("send classified response");
    });
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    let mut fallback = DesyncGroup::new(1);
    fallback.matches.detect = DETECT_HTTP_BLOCKPAGE;
    fallback.policy.ext_socks = Some(UpstreamSocksConfig { addr: alternate.local_addr().expect("alternate address") });
    config.groups.push(fallback);
    let telemetry = Arc::new(super::RecordingTelemetry::default());
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));
    let mut client = socks_connect(proxy.port, port);
    client.write_all(b"POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 8192\r\n\r\n").expect("send headers");
    headers_rx.recv_timeout(Duration::from_secs(2)).expect("primary has headers");
    client.write_all(&[0x53; 8192]).expect("send continuation");
    client.shutdown(Shutdown::Write).expect("finish upload");
    let mut received = Vec::new();
    let read = client.read_to_end(&mut received);
    let _ = client.shutdown(Shutdown::Both);
    drop(proxy);
    server.join().expect("join primary origin");
    read.expect("deliver terminal classified response");
    assert_eq!(received, response, "the rejected response is delivered exactly once");
    assert!(
        alternate.accept().is_err_and(|error| error.kind() == std::io::ErrorKind::WouldBlock),
        "request was replayed through the alternate upstream"
    );
    let snapshot = telemetry.snapshot();
    assert_eq!(telemetry.failures.load(std::sync::atomic::Ordering::Relaxed), 1);
    assert!(snapshot.routes.iter().all(|route| route.phase != "advanced"));
    assert!(
        snapshot.autolearn_events.iter().all(|event| event.action != "host_promoted"),
        "a classified response must not become an autolearn success"
    );
}
