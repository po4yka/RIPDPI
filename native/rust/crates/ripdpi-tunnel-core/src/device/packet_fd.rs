//! Nonblocking I/O for an owned descriptor that carries raw IP packets.

use std::fs::File;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, OwnedFd};

use tokio::io::Interest;
use tokio::io::unix::AsyncFd;

/// Owns a raw packet descriptor without creating or configuring an interface.
///
/// The descriptor must carry one raw IP packet per read/write, without a PI or
/// virtual-network header. Android VpnService and the socketpair harness use
/// this framing. Construct this adapter inside a Tokio runtime.
pub struct PacketFd {
    io: AsyncFd<File>,
}

impl PacketFd {
    pub fn new(fd: OwnedFd) -> io::Result<Self> {
        let file = File::from(fd);
        // SAFETY: File owns this open descriptor. F_GETFL takes no third argument
        // and does not change descriptor ownership.
        let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
        if flags == -1 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: File keeps the descriptor open. F_SETFL takes the integer flag
        // mask; existing status flags are preserved and ownership is unchanged.
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } == -1 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: File owns a stable open descriptor. The private AsyncFd keeps
        // File alive, and no method exposes or replaces its owned descriptor.
        let io = unsafe { AsyncFd::register(file) }.map_err(io::Error::from)?;
        Ok(Self { io })
    }

    /// # Cancel safety
    ///
    /// cancel-safe: this waits for readiness without reading a packet.
    pub async fn readable(&self) -> io::Result<()> {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        let interest = Interest::READABLE | Interest::ERROR;
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        let interest = Interest::READABLE;
        self.io.ready(interest).await.map(|_| ())
    }

    /// # Cancel safety
    ///
    /// cancel-safe: this waits for readiness without writing a packet.
    pub async fn writable(&self) -> io::Result<()> {
        self.io.writable().await.map(|_| ())
    }

    pub fn try_recv(&self, packet: &mut [u8]) -> io::Result<usize> {
        let result = self.io.try_io(Interest::READABLE, |mut file| file.read(packet));
        // A deleted Linux TUN can report only ERROR. Check each interest alone:
        // AsyncFd::try_io does not support a combined readiness mask.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        if matches!(&result, Err(error) if error.kind() == io::ErrorKind::WouldBlock) {
            return self.io.try_io(Interest::ERROR, |mut file| file.read(packet));
        }
        result
    }

    pub fn try_send(&self, packet: &[u8]) -> io::Result<usize> {
        self.io.try_io(Interest::WRITABLE, |mut file| file.write(packet))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixDatagram;
    use std::time::Duration;

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: all descriptor ownership is local; readiness waits consume no packet.
    async fn packet_bytes_and_readiness_survive_empty_reads() {
        let (device, peer) = UnixDatagram::pair().expect("socketpair");
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
        let packet_fd = PacketFd::new(device.into()).expect("adopt packet descriptor");
        let mut buffer = [0; 64];
        assert_eq!(packet_fd.try_recv(&mut buffer).expect_err("empty input").kind(), io::ErrorKind::WouldBlock);

        for packet in [&b"\x45\x00\x00\x14ipv4"[..], &b"\x60\x00\x00\x00ipv6"[..]] {
            peer.send(packet).expect("inject packet");
            tokio::time::timeout(Duration::from_secs(1), packet_fd.readable())
                .await
                .expect("readiness deadline")
                .expect("readiness");
            let len = packet_fd.try_recv(&mut buffer).expect("read packet");
            assert_eq!(&buffer[..len], packet);
            assert_eq!(packet_fd.try_recv(&mut buffer).expect_err("drained input").kind(), io::ErrorKind::WouldBlock);
            packet_fd.writable().await.expect("write readiness");
            assert_eq!(packet_fd.try_send(packet).expect("write packet"), packet.len());
            let len = peer.recv(&mut buffer).expect("capture packet");
            assert_eq!(&buffer[..len], packet);
        }
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: this test has no await point and owns both packet endpoints.
    async fn adoption_preserves_flags_and_drop_closes_the_endpoint() {
        let (device, peer) = UnixDatagram::pair().expect("socketpair");
        // SAFETY: device owns an open descriptor; F_GETFL only reads its flags.
        let before = unsafe { libc::fcntl(device.as_raw_fd(), libc::F_GETFL) };
        assert_ne!(before, -1);
        let packet_fd = PacketFd::new(device.into()).expect("adopt packet descriptor");
        // SAFETY: the adapter owns this open File; F_GETFL only reads its flags.
        let after = unsafe { libc::fcntl(packet_fd.io.get_ref().as_raw_fd(), libc::F_GETFL) };
        assert_eq!(after, before | libc::O_NONBLOCK);
        drop(packet_fd);
        assert!(peer.send(b"closed").is_err(), "the owned endpoint must close on drop");
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: the readiness wait consumes no data, and the test owns both endpoints.
    async fn error_only_readiness_returns_the_descriptor_error() {
        let destination = std::net::UdpSocket::bind("127.0.0.1:0").expect("destination");
        let sender = std::net::UdpSocket::bind("127.0.0.1:0").expect("sender");
        sender.connect(destination.local_addr().expect("destination address")).expect("connect");
        drop(destination);
        let packet_fd = PacketFd::new(sender.into()).expect("adopt packet descriptor");
        packet_fd.writable().await.expect("write readiness");
        assert_eq!(packet_fd.try_send(b"probe").expect("send probe"), 5);
        tokio::time::timeout(Duration::from_secs(2), packet_fd.readable())
            .await
            .expect("error-only readiness deadline")
            .expect("error readiness");
        let error = packet_fd.try_recv(&mut [0; 16]).expect_err("closed peer must report its error");
        assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    }
}
