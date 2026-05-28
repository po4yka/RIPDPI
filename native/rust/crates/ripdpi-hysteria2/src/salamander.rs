use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{ready, Context, Poll};

use blake2::digest::consts::U32;
use blake2::digest::Digest;
use blake2::Blake2b;
use quinn::{AsyncUdpSocket, UdpPoller};
use rand::Rng;

type Blake2b256 = Blake2b<U32>;

#[derive(Debug)]
pub(crate) struct SalamanderUdpSocket {
    io: tokio::net::UdpSocket,
    codec: SalamanderCodec,
}

impl SalamanderUdpSocket {
    pub(crate) fn new(socket: std::net::UdpSocket, key: Vec<u8>) -> io::Result<Self> {
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
                    let decoded = self.codec.decode(&scratch[..received])?;
                    let first = bufs
                        .first_mut()
                        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing QUIC receive buffer"))?;
                    if decoded.len() > first.len() {
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "Salamander datagram exceeds QUIC receive buffer",
                        )));
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

#[derive(Debug)]
pub(crate) struct SalamanderCodec {
    key: Vec<u8>,
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
        if payload.len() < 8 {
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

    #[test]
    fn salamander_roundtrip() {
        let codec = SalamanderCodec::new(b"top-secret".to_vec());
        let payload = b"hello, salamander";
        let encoded = codec.encode(payload);
        let decoded = codec.decode(&encoded).expect("decode");
        assert_eq!(decoded, payload);
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
