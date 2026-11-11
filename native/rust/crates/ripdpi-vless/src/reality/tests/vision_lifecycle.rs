use super::*;
use crate::vision::VisionStream;
use std::io::Read;
use std::task::Poll;
use std::time::Instant;
use tokio::io::{AsyncWrite, AsyncWriteExt};

const UUID: [u8; 16] = [0x42; 16];
const DEADLINE: Duration = Duration::from_secs(5);

fn start_peer(
    handler: impl FnOnce(boring::ssl::SslStream<std::net::TcpStream>) + Send + 'static,
) -> (VlessRealityConfig, thread::JoinHandle<()>) {
    let context =
        fixture_context(FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET), None, Arc::new(Mutex::new(Vec::new())));
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind Vision lifecycle peer");
    listener.set_nonblocking(true).expect("nonblocking lifecycle accept");
    let address = listener.local_addr().expect("peer address");
    let peer = thread::spawn(move || {
        let deadline = Instant::now() + DEADLINE;
        let stream = loop {
            match listener.accept() {
                Ok((stream, _)) => break stream,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    assert!(Instant::now() < deadline, "Vision lifecycle accept deadline");
                    thread::sleep(Duration::from_millis(1));
                }
                Err(error) => panic!("accept Vision lifecycle client: {error}"),
            }
        };
        stream.set_nonblocking(false).expect("blocking lifecycle peer IO");
        stream.set_read_timeout(Some(DEADLINE)).expect("read deadline");
        stream.set_write_timeout(Some(DEADLINE)).expect("write deadline");
        socket2::SockRef::from(&stream).set_recv_buffer_size(4096).expect("bound receive buffer");
        let tls = Ssl::new(&context).expect("peer SSL").accept(stream).expect("authenticated TLS handshake");
        handler(tls);
    });
    let public = PublicKey::from(&StaticSecret::from(REALITY_SERVER_SECRET));
    let key = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(public.as_bytes());
    let config = VlessRealityConfig::from_strings(
        "127.0.0.1",
        i32::from(address.port()),
        "42424242-4242-4242-4242-424242424242",
        "reality.test",
        &key,
        "abcd1234",
        "chrome_stable",
    )
    .expect("Vision lifecycle profile");
    (config, peer)
}

/// # Cancel safety:
/// cancel-safe: an incomplete handshake drops its owned socket; peer accept and IO have deadlines.
async fn connect(config: &VlessRealityConfig) -> VisionStream<super::super::RealityTlsStream<tokio::net::TcpStream>> {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", config.port)).await.expect("connect lifecycle peer");
    socket2::SockRef::from(&tcp).set_send_buffer_size(4096).expect("bound send buffer");
    let tls = connect_reality_tls(tcp, config).await.expect("authenticate lifecycle peer");
    VisionStream::new_vision(tls, UUID)
}

fn chunk(first: bool, command: u8, content: &[u8]) -> Vec<u8> {
    let mut wire = if first { UUID.to_vec() } else { Vec::new() };
    wire.push(command);
    wire.extend_from_slice(&u16::try_from(content.len()).expect("fixture content length").to_be_bytes());
    wire.extend_from_slice(&[0, 0]);
    wire.extend_from_slice(content);
    wire
}

fn read_chunk(reader: &mut impl Read) -> (u8, Vec<u8>) {
    let mut uuid = [0; 16];
    reader.read_exact(&mut uuid).expect("first uplink UUID");
    assert_eq!(uuid, UUID);
    let mut header = [0; 5];
    reader.read_exact(&mut header).expect("uplink chunk header");
    let mut content = vec![0; usize::from(u16::from_be_bytes([header[1], header[2]]))];
    reader.read_exact(&mut content).expect("uplink content");
    let mut padding = vec![0; usize::from(u16::from_be_bytes([header[3], header[4]]))];
    reader.read_exact(&mut padding).expect("uplink padding");
    (header[0], content)
}

fn server_hello() -> Vec<u8> {
    // TLS 1.3 ServerHello: empty session ID, AES-128-GCM, supported_versions.
    let mut record = vec![0x16, 3, 3, 0, 50, 2, 0, 0, 46, 3, 3];
    record.extend_from_slice(&[0x5a; 32]);
    record.extend_from_slice(&[0, 0x13, 1, 0, 0, 6, 0, 43, 0, 2, 3, 4]);
    record
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned client; peer accept and IO have deadlines.
#[tokio::test]
async fn downlink_direct_keeps_uplink_tls_half_close_and_delayed_raw_bytes() {
    let (config, peer) = start_peer(|mut tls| {
        tls.write_all(&chunk(true, 2, b"prefix")).expect("padded Direct prefix");
        tls.get_mut().write_all(b"adjacent").expect("raw bytes after outer TLS record");
        let mut byte = [0];
        assert_eq!(tls.read(&mut byte).expect("outer TLS close_notify"), 0);
        tls.get_mut().write_all(b"after-fin").expect("raw reply after uplink FIN");
    });
    tokio::time::timeout(DEADLINE, async {
        let mut stream = connect(&config).await;
        let mut prefix = [0; 14];
        stream.read_exact(&mut prefix).await.expect("Direct prefix and adjacent raw bytes");
        assert_eq!(&prefix, b"prefixadjacent");
        stream.shutdown().await.expect("TLS uplink half-close");
        let mut tail = Vec::new();
        stream.read_to_end(&mut tail).await.expect("remaining raw downlink");
        assert_eq!(tail, b"after-fin");
    })
    .await
    .expect("downlink Direct half-close deadline");
    peer.join().expect("downlink Direct peer");
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned client; peer accept and IO have deadlines.
#[tokio::test]
async fn uplink_direct_keeps_delayed_outer_tls_reply_after_fin() {
    const UPLINK: &[u8] = &[0x16, 3, 3, 0, 1, 1, 0x17, 3, 3, 0, 1, 0xaa];
    let (direct_seen, ready) = tokio::sync::oneshot::channel();
    let (config, peer) = start_peer(move |mut tls| {
        tls.write_all(&chunk(true, 0, &server_hello())).expect("padded inner ServerHello");
        assert_eq!(read_chunk(&mut tls), (2, UPLINK.to_vec()), "uplink must announce Direct before raw bytes");
        direct_seen.send(()).expect("notify client after decoding Direct");
        let mut raw = Vec::new();
        tls.get_mut().read_to_end(&mut raw).expect("raw uplink until FIN");
        assert_eq!(raw, b"raw-uplink");
        tls.write_all(&chunk(false, 1, b"after-fin")).expect("outer TLS reply after raw FIN");
        tls.shutdown().expect("outer TLS close_notify");
    });
    tokio::time::timeout(DEADLINE, async {
        let mut stream = connect(&config).await;
        let mut hello = vec![0; server_hello().len()];
        stream.read_exact(&mut hello).await.expect("inner ServerHello");
        assert_eq!(hello, server_hello());
        stream.write_all(UPLINK).await.expect("complete inner records");
        stream.flush().await.expect("flush Direct before raw bytes");
        ready.await.expect("peer decoded Direct");
        stream.write_all(b"raw-uplink").await.expect("raw uplink after Direct");
        stream.shutdown().await.expect("raw uplink half-close");
        let mut reply = Vec::new();
        stream.read_to_end(&mut reply).await.expect("outer TLS downlink after raw FIN");
        assert_eq!(reply, b"after-fin");
    })
    .await
    .expect("uplink Direct half-close deadline");
    peer.join().expect("uplink Direct peer");
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the client; peer accept, release wait and IO have deadlines.
#[tokio::test]
async fn dropping_a_pending_flush_closes_the_owned_socket() {
    let (release, wait) = std::sync::mpsc::channel();
    let (config, peer) = start_peer(move |mut tls| {
        wait.recv_timeout(DEADLINE).expect("client released peer after drop");
        let mut bytes = Vec::new();
        tls.get_mut().read_to_end(&mut bytes).expect("owned socket EOF after cancellation");
        assert!(!bytes.is_empty(), "the cancelled write must have reached the real socket");
    });
    tokio::time::timeout(DEADLINE, async {
        let mut stream = connect(&config).await;
        stream.write_all(&vec![0x41; 4 * 1024 * 1024]).await.expect("accept padded write");
        let pending = poll_fn(|cx| Poll::Ready(Pin::new(&mut stream).poll_flush(cx).is_pending())).await;
        assert!(pending, "the bounded socket must apply backpressure before cancellation");
        drop(stream);
        release.send(()).expect("release reader after cancelling flush");
    })
    .await
    .expect("pending flush cancellation deadline");
    peer.join().expect("cancelled flush peer");
}
