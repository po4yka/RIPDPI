use super::*;
use crate::cipher;
use crate::config::{MieruMux, MieruProtocol};
use crate::traffic_pattern::tests::pattern;

const NOW: i64 = 1_700_000_000;

fn config() -> MieruConfig {
    MieruConfig {
        server: "loopback.invalid".into(),
        port: 443,
        username: "alice".into(),
        password: "fixture".into(),
        protocol: MieruProtocol::Tcp,
        multiplexing: MieruMux::Low,
        mtu: 1400,
        handshake_no_wait: true,
        traffic_pattern: Some(pattern()),
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns and explicitly closes its stream and carrier before it returns.
async fn no_wait_payload_case(reply_case: u8) {
    let config = config();
    let key = cipher::derive_key(config.password.as_bytes(), config.username.as_bytes(), NOW, 0);
    let (client, server) = tokio::io::duplex(65536);
    let time = Arc::new(NetworkTimeProvider::fixed(NOW));
    let connection = MieruMuxConnection::connect_over(client, &config, Arc::clone(&time)).await.expect("carrier");
    let mut stream = timeout(Duration::from_secs(1), connection.open_stream("target.invalid:443"))
        .await
        .expect("no_wait returns without a server reply")
        .expect("stream");
    assert!(!time.is_calibrated());
    stream.write_all(b"early payload").await.expect("queue early payload");
    let peer = tokio::spawn(async move {
        let (mut read, mut write) = tokio::io::split(server);
        let mut decoder = Decryptor::new(key);
        let mut encoder = Encryptor::new(key, b"alice".to_vec());
        let (open, _) = decoder.read_segment(&mut read).await.expect("open");
        assert_eq!(open[0], ProtocolType::OpenSessionRequest.as_u8());
        let (request_meta, request) = decoder.read_segment(&mut read).await.expect("request");
        assert_eq!(&request.expect("request bytes")[..4], &[5, 1, 0, 3]);
        let (_, payload) = decoder.read_segment(&mut read).await.expect("payload arrives before reply");
        let payload = payload.expect("early payload bytes");
        assert_eq!(payload, b"early payload");
        let mut reply = if reply_case == 1 { [5, 5, 0, 1, 0, 0, 0, 0, 0, 0] } else { [5, 0, 0, 1, 0, 0, 0, 0, 0, 0] };
        if reply_case == 2 {
            reply[2] = 1;
        }
        let response = [reply.as_slice(), payload.as_slice()].concat();
        encoder
            .write_segment(
                &mut write,
                0,
                0,
                |prefix_len, suffix_len| {
                    DataAckMeta {
                        protocol: ProtocolType::DataServerToClient,
                        session_id: session_id_of(&request_meta),
                        seq: 0,
                        unack_seq: 0,
                        window_size: 0,
                        fragment: 0,
                        prefix_len,
                        payload_len: u16::try_from(response.len()).expect("short response"),
                        suffix_len,
                    }
                    .marshal(NOW)
                },
                Some(&response),
            )
            .await
            .expect("response");
        let mut sink = [0; 4096];
        while read.read(&mut sink).await.is_ok_and(|count| count != 0) {}
    });
    if reply_case != 0 {
        let error =
            timeout(Duration::from_secs(1), stream.read_u8()).await.expect("refusal deadline").expect_err("refusal");
        assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
        assert!(stream.write_all(b"after refusal").await.is_err());
    } else {
        let mut received = [0; 13];
        timeout(Duration::from_secs(1), stream.read_exact(&mut received))
            .await
            .expect("payload deadline")
            .expect("payload");
        assert_eq!(&received, b"early payload");
    }
    assert_eq!(time.is_calibrated(), reply_case == 0);
    stream.close().await.expect("stream close");
    connection.close().await.expect("carrier close");
    timeout(Duration::from_secs(1), peer).await.expect("peer close").expect("peer task");
    assert!(connection.table.lock().expect("table").is_empty());
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can consume part of the reply in the fixture.
#[tokio::test]
async fn buffered_success_after_registration_removal_does_not_calibrate() {
    let time = Arc::new(NetworkTimeProvider::fixed(NOW));
    let reply_clock = ReplyClock {
        time: Arc::clone(&time),
        source: config().time_source(),
        authenticated: AtomicBool::new(false),
        calibrated: AtomicBool::new(false),
    };
    let (tx, rx) = mpsc::channel(1);
    let table: DemuxTable = Arc::default();
    let mut reader = MuxReader::new(rx, Arc::default(), Arc::default());
    assert!(tx.try_send(InboundChunk { bytes: vec![5, 0, 0, 1, 0, 0, 0, 0, 0, 0], timestamp: NOW }).is_ok());
    reply_clock.receive_reply(&mut reader, &table, 1).await.expect("parse response");
    assert!(!time.is_calibrated());
    assert!(!reply_clock.authenticated.load(Ordering::Acquire));
}

/// # Cancel safety:
/// NOT cancel-safe: the helper explicitly joins all owned test resources.
#[tokio::test]
async fn no_wait_removes_the_reply_before_forwarding_early_payload() {
    no_wait_payload_case(0).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper explicitly joins all owned test resources.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_wait_refusal_fails_read_and_write_multithread() {
    no_wait_payload_case(1).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test explicitly closes its stream, carrier and peer.
#[tokio::test(start_paused = true)]
async fn silent_no_wait_reply_times_out_and_close_joins_resources() {
    let (client, server) = tokio::io::duplex(65536);
    let peer = tokio::spawn(async move {
        let (mut read, _write) = tokio::io::split(server);
        let mut bytes = [0; 4096];
        while read.read(&mut bytes).await.is_ok_and(|count| count != 0) {}
    });
    let connection = MieruMuxConnection::connect_over(client, &config(), Arc::new(NetworkTimeProvider::fixed(NOW)))
        .await
        .expect("carrier");
    let mut stream = connection.open_stream("target.invalid:443").await.expect("no_wait stream");
    assert_eq!(stream.read_u8().await.expect_err("timeout must fail read").kind(), std::io::ErrorKind::ConnectionReset);
    stream.close().await.expect("stream close");
    connection.close().await.expect("carrier close");
    peer.await.expect("peer");
    assert!(connection.streams.lock().expect("streams").groups.is_empty());
}

/// # Cancel safety:
/// NOT cancel-safe: the test explicitly closes its stream, carrier and peer.
#[tokio::test]
async fn cancel_no_wait_before_reply_releases_its_registration() {
    let (client, server) = tokio::io::duplex(65536);
    let peer = tokio::spawn(async move {
        let (mut read, _write) = tokio::io::split(server);
        let mut bytes = [0; 4096];
        while read.read(&mut bytes).await.is_ok_and(|count| count != 0) {}
    });
    let connection = MieruMuxConnection::connect_over(client, &config(), Arc::new(NetworkTimeProvider::fixed(NOW)))
        .await
        .expect("carrier");
    let mut stream = connection.open_stream("target.invalid:443").await.expect("no_wait stream");
    timeout(Duration::from_secs(1), stream.close()).await.expect("cancel deadline").expect("close");
    assert!(connection.table.lock().expect("table").is_empty());
    assert_eq!(connection.limit.available_permits(), crate::config::CARRIER_STREAM_LIMIT);
    connection.close().await.expect("carrier close");
    peer.await.expect("peer");
}

/// # Cancel safety:
/// NOT cancel-safe: the helper explicitly joins all owned test resources.
#[tokio::test]
async fn no_wait_malformed_reply_fails_read_and_write() {
    no_wait_payload_case(2).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns and joins its carrier, logical stream and stalled peer.
async fn backpressured_no_wait_case(refuse: bool) {
    use std::future::Future;
    use std::task::Poll;

    let config = config();
    let key = cipher::derive_key(config.password.as_bytes(), config.username.as_bytes(), NOW, 0);
    let (client, server) = tokio::io::duplex(1024);
    let (ready_tx, ready_rx) = oneshot::channel();
    let (stop_tx, stop_rx) = oneshot::channel();
    let (blocked_tx, blocked_rx) = oneshot::channel();
    let (reply_tx, reply_rx) = oneshot::channel();
    let peer = tokio::spawn(async move {
        let (mut read, mut write) = tokio::io::split(server);
        let mut decoder = Decryptor::new(key);
        decoder.read_segment(&mut read).await.expect("open frame");
        let (request_meta, request) = decoder.read_segment(&mut read).await.expect("CONNECT frame");
        assert_eq!(&request.expect("CONNECT bytes")[..4], &[5, 1, 0, 3]);
        ready_tx.send(()).expect("notify accepted CONNECT");
        // Eight bytes cannot drain the next 32 KiB frame from this 1 KiB pipe.
        // The physical writer is now blocked while the logical pump awaits it.
        let mut partial_frame = [0; 8];
        read.read_exact(&mut partial_frame).await.expect("partial application frame");
        blocked_tx.send(()).expect("notify blocked physical writer");
        if refuse {
            reply_rx.await.expect("reply only after caller write is pending");
            let mut encoder = Encryptor::new(key, b"alice".to_vec());
            let reply = [5, 5, 0, 1, 0, 0, 0, 0, 0, 0];
            encoder
                .write_segment(
                    &mut write,
                    0,
                    0,
                    |prefix_len, suffix_len| {
                        DataAckMeta {
                            protocol: ProtocolType::DataServerToClient,
                            session_id: session_id_of(&request_meta),
                            seq: 0,
                            unack_seq: 0,
                            window_size: 0,
                            fragment: 0,
                            prefix_len,
                            payload_len: 10,
                            suffix_len,
                        }
                        .marshal(NOW)
                    },
                    Some(&reply),
                )
                .await
                .expect("send refusal while client writer is blocked");
        }
        // Keep both transport halves alive without draining the blocked frame.
        let _ = stop_rx.await;
    });
    let connection = MieruMuxConnection::connect_over(client, &config, Arc::new(NetworkTimeProvider::fixed(NOW)))
        .await
        .expect("carrier");
    let mut stream = connection.open_stream("target.invalid:443").await.expect("no_wait stream");
    ready_rx.await.expect("peer accepted CONNECT");
    let payload = vec![0x5a; BRIDGE_BUF * 32];
    let (started_tx, started_rx) = oneshot::channel();
    let mut writing = tokio::spawn(async move {
        let result = {
            let mut pending = Box::pin(stream.write_all(&payload));
            std::future::poll_fn(|context| {
                assert!(
                    pending.as_mut().poll(context).is_pending(),
                    "the stalled carrier must backpressure the caller"
                );
                Poll::Ready(())
            })
            .await;
            started_tx.send(()).expect("caller write is blocked");
            pending.await
        };
        (stream, result)
    });
    started_rx.await.expect("caller write started");
    blocked_rx.await.expect("physical writer is blocked");
    if refuse {
        reply_tx.send(()).expect("request refusal");
    }
    // Timing only the JoinHandle cannot accidentally repoll the blocked write.
    let outcome = timeout(HANDSHAKE_TIMEOUT + Duration::from_secs(2), &mut writing).await;
    if outcome.is_err() {
        writing.abort();
        let _ = writing.await;
        stop_tx.send(()).expect("release stalled peer after failure");
        connection.close().await.expect("join failed-test carrier");
        peer.await.expect("join failed-test peer");
        panic!("the handshake failure must wake a pending caller write without read or close");
    }
    let (mut stream, result) = outcome.expect("write wakeup deadline").expect("caller writer task");
    let failure = result.expect_err("silent peer cannot accept a complete write");
    assert_eq!(failure.kind(), std::io::ErrorKind::ConnectionReset);
    stream.close().await.expect("close logical stream");
    stop_tx.send(()).expect("release stalled peer");
    connection.close().await.expect("join carrier");
    peer.await.expect("join peer");
    assert!(connection.table.lock().expect("table").is_empty());
    assert!(connection.streams.lock().expect("groups").groups.is_empty());
}

/// # Cancel safety:
/// NOT cancel-safe: the helper closes and joins every owned test resource.
#[tokio::test(start_paused = true)]
async fn backpressured_no_wait_writer_fails_on_timeout() {
    backpressured_no_wait_case(false).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper closes and joins every owned test resource.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn backpressured_no_wait_writer_fails_on_timeout_multithread() {
    backpressured_no_wait_case(false).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper closes and joins every owned test resource.
#[tokio::test(start_paused = true)]
async fn backpressured_no_wait_writer_fails_on_refusal() {
    backpressured_no_wait_case(true).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the helper closes and joins every owned test resource.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn backpressured_no_wait_writer_fails_on_refusal_multithread() {
    backpressured_no_wait_case(true).await;
}
