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
}
