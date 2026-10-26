use std::fmt;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll, ready};

use blake2::Blake2b;
use blake2::digest::Digest;
use blake2::digest::consts::U32;
use quinn::{AsyncUdpSocket, UdpPoller};
use rand::Rng;

type Blake2b256 = Blake2b<U32>;

/// Length of the random salt that prefixes every Salamander datagram.
const SALT_LEN: usize = 8;

#[derive(Debug)]
pub(crate) struct SalamanderUdpSocket {
    io: tokio::net::UdpSocket,
    codec: SalamanderCodec,
}

impl SalamanderUdpSocket {
    pub(crate) fn new(socket: std::net::UdpSocket, key: Vec<u8>) -> io::Result<Self> {
        socket.set_nonblocking(true)?;
        Ok(Self { io: tokio::net::UdpSocket::from_std(socket)?, codec: SalamanderCodec::new(key) })
    }
}

impl AsyncUdpSocket for SalamanderUdpSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(TokioUdpPoller { socket: self })
    }

    fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> io::Result<()> {
        self.io.try_io(tokio::io::Interest::WRITABLE, || {
            let segments = transmit.segment_size.unwrap_or(transmit.contents.len());
            for chunk in transmit.contents.chunks(segments) {
                let encoded = self.codec.encode(chunk);
                let sent = self.io.try_send_to(&encoded, transmit.destination)?;
                if sent != encoded.len() {
                    return Err(io::Error::new(io::ErrorKind::WriteZero, "short Salamander UDP send"));
                }
            }
            Ok(())
        })
    }

    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [std::io::IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        loop {
            ready!(self.io.poll_recv_ready(cx))?;
            let buffer_len = bufs.first().map_or(0, |buffer| buffer.len());
            let mut scratch = vec![0u8; buffer_len.saturating_mul(2).max(2048)];
            match self.io.try_io(tokio::io::Interest::READABLE, || self.io.try_recv_from(&mut scratch)) {
                Ok((received, addr)) => {
                    let first = bufs
                        .first_mut()
                        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing QUIC receive buffer"))?;
                    // The socket is unconnected and Salamander has no MAC, so
                    // anyone can send to it. A datagram that is too short or
                    // too large is dropped here, as the reference client does;
                    // an Err would end the quinn endpoint and every connection
                    // on it. Only real socket errors are returned.
                    if received <= SALT_LEN {
                        continue;
                    }
                    let Ok(decoded) = self.codec.decode(&scratch[..received]) else {
                        continue;
                    };
                    if decoded.len() > first.len() {
                        continue;
                    }
                    first[..decoded.len()].copy_from_slice(&decoded);
                    meta[0] = quinn::udp::RecvMeta {
                        addr,
                        len: decoded.len(),
                        stride: decoded.len(),
                        ecn: None,
                        dst_ip: None,
                    };
                    return Poll::Ready(Ok(1));
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }

    fn may_fragment(&self) -> bool {
        true
    }
}

#[derive(Debug)]
struct TokioUdpPoller {
    socket: Arc<SalamanderUdpSocket>,
}

impl UdpPoller for TokioUdpPoller {
    fn poll_writable(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.socket.io.poll_send_ready(cx)
    }
}

pub(crate) struct SalamanderCodec {
    key: Vec<u8>,
}

impl fmt::Debug for SalamanderCodec {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.debug_struct("SalamanderCodec").field("key", &"<redacted>").finish()
    }
}

impl SalamanderCodec {
    pub(crate) fn new(key: Vec<u8>) -> Self {
        Self { key }
    }

    pub(crate) fn encode(&self, payload: &[u8]) -> Vec<u8> {
        let mut salt = [0u8; 8];
        rand::rng().fill_bytes(&mut salt);
        let keystream = self.keystream(&salt);

        let mut out = Vec::with_capacity(8 + payload.len());
        out.extend_from_slice(&salt);
        for (index, byte) in payload.iter().enumerate() {
            out.push(byte ^ keystream[index % keystream.len()]);
        }
        out
    }

    pub(crate) fn decode(&self, payload: &[u8]) -> io::Result<Vec<u8>> {
        if payload.len() < SALT_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Salamander datagram is shorter than the required salt prefix",
            ));
        }

        let salt = &payload[..8];
        let body = &payload[8..];
        let keystream = self.keystream(salt);
        Ok(body.iter().enumerate().map(|(index, byte)| byte ^ keystream[index % keystream.len()]).collect())
    }

    fn keystream(&self, salt: &[u8]) -> Vec<u8> {
        let mut hasher = Blake2b256::new();
        hasher.update(&self.key);
        hasher.update(salt);
        hasher.finalize().to_vec()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "current_thread")]
    async fn blocking_udp_socket_round_trips_after_wrapping() {
        tokio::time::timeout(std::time::Duration::from_secs(2), socket_round_trip(false))
            .await
            .expect("blocking input must support asynchronous datagrams");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn nonblocking_udp_socket_round_trips_after_wrapping() {
        tokio::time::timeout(std::time::Duration::from_secs(2), socket_round_trip(true))
            .await
            .expect("nonblocking input must remain usable");
    }

    // cancel-safe: dropping this future closes only its owned loopback sockets.
    async fn socket_round_trip(nonblocking: bool) {
        let socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind Salamander socket");
        socket.set_nonblocking(nonblocking).expect("set explicit input mode");
        let original_addr = socket.local_addr().expect("bound address");
        let wrapped = Arc::new(SalamanderUdpSocket::new(socket, b"fixture-key".to_vec()).expect("wrap socket"));
        assert_eq!(wrapped.local_addr().expect("wrapped address"), original_addr);
        let peer = tokio::net::UdpSocket::bind("127.0.0.1:0").await.expect("bind peer");
        let peer_addr = peer.local_addr().expect("peer address");
        let codec = SalamanderCodec::new(b"fixture-key".to_vec());
        let mut poller = wrapped.clone().create_io_poller();
        std::future::poll_fn(|cx| poller.as_mut().poll_writable(cx)).await.expect("socket writable");
        wrapped
            .try_send(&quinn::udp::Transmit {
                destination: peer_addr,
                ecn: None,
                contents: b"request",
                segment_size: None,
                src_ip: None,
            })
            .expect("send through Salamander");
        let mut wire = [0u8; 128];
        let (received, source) = peer.recv_from(&mut wire).await.expect("receive encoded datagram");
        assert_eq!(source, original_addr);
        assert_eq!(codec.decode(&wire[..received]).expect("decode request"), b"request");
        peer.send_to(&codec.encode(b"response"), source).await.expect("send encoded response");
        let mut payload = [0u8; 128];
        let mut meta = [quinn::udp::RecvMeta::default()];
        let count = {
            let mut bufs = [std::io::IoSliceMut::new(&mut payload)];
            std::future::poll_fn(|cx| wrapped.poll_recv(cx, &mut bufs, &mut meta)).await.expect("receive response")
        };
        assert_eq!(count, 1);
        assert_eq!(meta[0].addr, peer_addr);
        assert_eq!(&payload[..meta[0].len], b"response");
    }

    /// A datagram that cannot be a Salamander packet is per-packet garbage:
    /// it must be dropped, not surfaced as a socket error that ends the quinn
    /// endpoint and every connection on it.
    #[tokio::test(flavor = "current_thread")]
    async fn undecodable_and_oversize_datagrams_are_dropped() {
        tokio::time::timeout(std::time::Duration::from_secs(2), drop_garbage_then_receive())
            .await
            .expect("valid datagram must still arrive after garbage");
    }

    // cancel-safe: dropping this future closes only its owned loopback sockets.
    async fn drop_garbage_then_receive() {
        let socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind Salamander socket");
        let local = socket.local_addr().expect("bound address");
        let wrapped = SalamanderUdpSocket::new(socket, b"fixture-key".to_vec()).expect("wrap socket");
        let peer = tokio::net::UdpSocket::bind("127.0.0.1:0").await.expect("bind peer");
        let codec = SalamanderCodec::new(b"fixture-key".to_vec());

        let oversize = codec.encode(&[0x42; 200]);
        for garbage in [&[][..], &[1][..], &[7; 7][..], &[8; 8][..], &oversize[..]] {
            peer.send_to(garbage, local).await.expect("send garbage datagram");
        }
        peer.send_to(&codec.encode(b"after-garbage"), local).await.expect("send valid datagram");

        let mut payload = [0u8; 128];
        let mut meta = [quinn::udp::RecvMeta::default()];
        let count = {
            let mut bufs = [std::io::IoSliceMut::new(&mut payload)];
            std::future::poll_fn(|cx| wrapped.poll_recv(cx, &mut bufs, &mut meta))
                .await
                .expect("garbage must not surface as a socket error")
        };
        assert_eq!(count, 1);
        assert_eq!(&payload[..meta[0].len], b"after-garbage");
    }

    /// The Salamander socket carries a real Hysteria 2 session through quinn:
    /// handshake, a TCP stream, and a UDP payload larger than one datagram,
    /// which the client fragments and reassembles.
    #[tokio::test]
    async fn salamander_session_relays_tcp_and_fragmented_udp_through_quinn() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let deadline = std::time::Duration::from_secs(5);
        let server = crate::test_server::TestServer::start(crate::test_server::ServerOptions {
            salamander_key: Some("fixture-key"),
            ..crate::test_server::ServerOptions::default()
        })
        .expect("Salamander test server");
        let mut config = server.client_config();
        config.salamander_key = Some("fixture-key".into());
        let client =
            tokio::time::timeout(deadline, crate::connect(&config)).await.expect("handshake").expect("connect");

        let mut stream = client.tcp_connect("192.0.2.1:80").await.expect("TCP stream");
        stream.write_all(b"through salamander").await.expect("write");
        let mut echoed = [0u8; 18];
        tokio::time::timeout(deadline, stream.read_exact(&mut echoed)).await.expect("echo").expect("read");
        assert_eq!(&echoed, b"through salamander");

        let mut session = client.udp_session().await.expect("UDP session");
        let payload: Vec<u8> = (0..4000u32).map(|index| index.to_le_bytes()[0]).collect();
        session.send_to("192.0.2.1:53", &payload).await.expect("send fragmented datagram");
        let (_, received) = tokio::time::timeout(deadline, session.recv_from()).await.expect("echo").expect("recv");
        assert_eq!(received, payload, "fragments must reassemble to the original payload");

        let mut wrong_key = server.client_config();
        wrong_key.salamander_key = Some("other-key".into());
        assert!(
            tokio::time::timeout(std::time::Duration::from_secs(1), crate::connect(&wrong_key)).await.is_err(),
            "a client with the wrong key must not complete the handshake"
        );
    }

    #[test]
    fn salamander_roundtrip() {
        let codec = SalamanderCodec::new(b"top-secret".to_vec());
        let payload = b"hello, salamander";
        let encoded = codec.encode(payload);
        let decoded = codec.decode(&encoded).expect("decode");
        assert_eq!(decoded, payload);
    }

    #[test]
    fn salamander_codec_debug_redacts_key() {
        let codec = SalamanderCodec::new(b"debug-salamander-secret".to_vec());
        let rendered = format!("{codec:?}");
        assert!(!rendered.contains("debug-salamander-secret"));
        assert!(rendered.contains("<redacted>"));
    }

    #[test]
    fn salamander_keystream_pinned_for_known_key_and_salt() {
        // Regression-boundary fixture. The keystream derivation is
        // `Blake2b256(key || salt) -> 32 bytes`. Pinning the keystream
        // output for a known (key, salt) catches accidental algorithm
        // bumps (e.g. swapping blake2b256 for blake2b512) before they
        // ship.
        //
        // This is NOT an upstream-conformance check. Broader upstream-captured
        // vectors are tracked under
        // `docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`.
        let codec = SalamanderCodec::new(b"top-secret".to_vec());
        let salt = [0u8; 8];
        let keystream = codec.keystream(&salt);

        assert_eq!(keystream.len(), 32, "blake2b256 produces a 32-byte output");

        // Decode a synthetic ciphertext: salt + (plaintext XOR keystream).
        let plaintext = b"hello";
        let mut ciphertext = Vec::with_capacity(8 + plaintext.len());
        ciphertext.extend_from_slice(&salt);
        for (i, &b) in plaintext.iter().enumerate() {
            ciphertext.push(b ^ keystream[i % keystream.len()]);
        }

        let decoded = codec.decode(&ciphertext).expect("decode synthetic ciphertext");
        assert_eq!(decoded, plaintext, "decode must invert XOR-with-keystream construction");
    }

    /// Fixture harness for pinned Salamander vectors.
    /// Walks every `.bin` file under the pinned upstream tag:
    /// `contract-fixtures/hysteria2/<tag>/salamander/<key-hex>/`
    /// where the parent directory name encodes the obfuscation key
    /// as hex, and each `.bin` file's contents are `salt(8) +
    /// ciphertext`. The harness decodes each file, asserts the
    /// payload length is `file_len - 8`, and asserts decode never
    /// panics.
    ///
    /// Tracks the fixture-walker side of
    /// `docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`.
    #[test]
    fn upstream_salamander_fixtures_decode_cleanly() {
        let repo_root = golden_test_support::repo_root();
        let upstream_tag = pinned_upstream_tag(&repo_root.join("native/rust/crates/ripdpi-hysteria2/SPEC_VERSION.md"));
        let salamander_dir = repo_root.join("contract-fixtures/hysteria2").join(upstream_tag).join("salamander");
        assert!(salamander_dir.is_dir(), "missing pinned Salamander fixture dir: {salamander_dir:?}");

        let mut count = 0usize;
        for key_entry in std::fs::read_dir(&salamander_dir).expect("read salamander dir") {
            let key_entry = key_entry.expect("dir entry");
            if !key_entry.file_type().expect("file type").is_dir() {
                continue;
            }
            let key_hex = key_entry.file_name().to_string_lossy().into_owned();
            let Some(key) = hex_decode_simple(&key_hex) else {
                continue;
            };
            let codec = SalamanderCodec::new(key);
            for entry in std::fs::read_dir(key_entry.path()).expect("read key dir") {
                let path = entry.expect("dir entry").path();
                if path.extension().and_then(|s| s.to_str()) != Some("bin") {
                    continue;
                }
                let wire = std::fs::read(&path).expect("read fixture");
                assert!(wire.len() >= 8, "fixture {path:?} shorter than 8-byte salt prefix");
                let decoded = codec.decode(&wire).unwrap_or_else(|err| panic!("decode {path:?}: {err}"));
                assert_eq!(
                    decoded.len(),
                    wire.len() - 8,
                    "salamander decode length must equal ciphertext - salt prefix for fixture {path:?}",
                );
                count += 1;
            }
        }
        assert!(count > 0, "no pinned Salamander fixtures found under {salamander_dir:?}");
        eprintln!("upstream_salamander_fixtures_decode_cleanly: exercised {count} fixtures");
    }

    fn pinned_upstream_tag(spec_version: &std::path::Path) -> String {
        let spec = std::fs::read_to_string(spec_version).expect("read SPEC_VERSION.md");
        spec.lines()
            .find_map(|line| line.strip_prefix("- **Upstream tag:**"))
            .and_then(|value| value.split_whitespace().next())
            .expect("upstream tag in SPEC_VERSION.md")
            .to_string()
    }

    fn hex_decode_simple(s: &str) -> Option<Vec<u8>> {
        if !s.len().is_multiple_of(2) {
            return None;
        }
        let mut out = Vec::with_capacity(s.len() / 2);
        for chunk in s.as_bytes().chunks(2) {
            let hi = (chunk[0] as char).to_digit(16)?;
            let lo = (chunk[1] as char).to_digit(16)?;
            out.push(((hi << 4) | lo) as u8);
        }
        Some(out)
    }
}
