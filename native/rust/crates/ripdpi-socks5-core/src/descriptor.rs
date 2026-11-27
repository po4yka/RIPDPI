//! Passes one descriptor over a connected Unix stream, as the byte `0x01` with `SCM_RIGHTS`.

use std::io::{self, IoSlice, IoSliceMut};
use std::mem::MaybeUninit;
use std::os::fd::{BorrowedFd, OwnedFd};

use rustix::cmsg_space;
use rustix::io::Errno;
use rustix::net::{
    AddressFamily, RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, ReturnFlags, SendAncillaryBuffer,
    SendAncillaryMessage, SendFlags, SocketType, getsockname, recvmsg, sendmsg, sockopt,
};

const MARKER: u8 = 0x01;
// Room for the largest SCM_RIGHTS message any supported kernel accepts (XNU: 512), so an
// extra descriptor is counted and refused instead of being cut off and left installed.
const MAX_ANCILLARY_FDS: usize = 512;

#[cfg(any(target_os = "linux", target_os = "android"))]
const RECV_FLAGS: RecvFlags = RecvFlags::CMSG_CLOEXEC;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
const RECV_FLAGS: RecvFlags = RecvFlags::empty();

#[cfg(any(target_os = "linux", target_os = "android"))]
const SEND_FLAGS: SendFlags = SendFlags::NOSIGNAL;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
const SEND_FLAGS: SendFlags = SendFlags::empty();

/// Sends one byte with exactly one descriptor attached.
///
/// On a non-blocking stream a full buffer returns `WouldBlock` and nothing is sent.
pub fn send_fd(stream: BorrowedFd<'_>, fd: BorrowedFd<'_>) -> io::Result<()> {
    let fds = [fd];
    let mut space = [MaybeUninit::uninit(); cmsg_space!(ScmRights(1))];
    let mut control = SendAncillaryBuffer::new(&mut space);
    if !control.push(SendAncillaryMessage::ScmRights(&fds)) {
        return Err(io::Error::other("descriptor control buffer too small"));
    }
    let iov = [IoSlice::new(&[MARKER])];
    loop {
        match sendmsg(stream, &iov, &mut control, SEND_FLAGS) {
            Ok(1) => return Ok(()),
            Ok(_) => return Err(io::Error::new(io::ErrorKind::WriteZero, "descriptor marker was not sent")),
            Err(Errno::INTR) => {}
            Err(error) => return Err(error.into()),
        }
    }
}

/// Receives one byte and exactly one descriptor.
///
/// Closes every received descriptor and returns an error when the byte is not `0x01`, the
/// count is not one, or the control data was truncated. Blocking and timeouts follow the
/// socket; on a non-blocking stream an empty socket returns `WouldBlock` and consumes nothing.
pub fn recv_fd(stream: BorrowedFd<'_>) -> io::Result<OwnedFd> {
    let mut marker = [0u8; 1];
    let mut space = [MaybeUninit::uninit(); cmsg_space!(ScmRights(MAX_ANCILLARY_FDS))];
    let mut control = RecvAncillaryBuffer::new(&mut space);
    let received = loop {
        let mut iov = [IoSliceMut::new(&mut marker)];
        match recvmsg(stream, &mut iov, &mut control, RECV_FLAGS) {
            Ok(received) => break received,
            Err(Errno::INTR) => {}
            Err(error) => return Err(error.into()),
        }
    };
    // Dropping the descriptors on any error below closes them.
    let mut fds: Vec<OwnedFd> = Vec::new();
    for message in control.drain() {
        if let RecvAncillaryMessage::ScmRights(received_fds) = message {
            fds.extend(received_fds);
        }
    }
    if received.bytes == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "descriptor marker missing"));
    }
    if received.flags.contains(ReturnFlags::CTRUNC) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "descriptor control data was truncated"));
    }
    if marker[0] != MARKER || fds.len() != 1 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "expected one marker byte with exactly one descriptor"));
    }
    let fd = fds.remove(0);
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    rustix::io::fcntl_setfd(&fd, rustix::io::FdFlags::CLOEXEC)?;
    Ok(fd)
}

/// True when `fd` is an `AF_UNIX` `SOCK_DGRAM` socket.
pub fn is_unix_datagram(fd: BorrowedFd<'_>) -> io::Result<bool> {
    Ok(sockopt::socket_type(fd)? == SocketType::DGRAM && getsockname(fd)?.address_family() == AddressFamily::UNIX)
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::os::fd::{AsFd, AsRawFd};
    use std::os::unix::net::{UnixDatagram, UnixStream};

    use super::*;

    fn closed_peer_observed(peer: &UnixDatagram) -> bool {
        // A datagram socket reports a closed peer on the next send to it.
        (0..50).any(|_| {
            std::thread::sleep(std::time::Duration::from_millis(20));
            peer.send(b"x").is_err()
        })
    }

    #[test]
    fn round_trip_yields_a_usable_distinct_descriptor() {
        let (a, b) = UnixStream::pair().expect("stream pair");
        let (datagram, far) = UnixDatagram::pair().expect("datagram pair");
        send_fd(a.as_fd(), datagram.as_fd()).expect("send");
        let received = recv_fd(b.as_fd()).expect("receive");
        assert_ne!(received.as_raw_fd(), datagram.as_raw_fd());
        let received = UnixDatagram::from(received);
        received.send(b"ping").expect("send on received end");
        let mut buf = [0u8; 4];
        assert_eq!(far.recv(&mut buf).expect("peer receive"), 4);
        assert_eq!(&buf, b"ping");
    }

    #[test]
    fn wrong_marker_byte_is_refused_and_the_descriptor_closed() {
        let (a, b) = UnixStream::pair().expect("stream pair");
        let (datagram, far) = UnixDatagram::pair().expect("datagram pair");
        raw_send(&a, &[0x02], &[datagram.as_fd()]);
        drop(datagram);
        assert_eq!(recv_fd(b.as_fd()).expect_err("marker").kind(), io::ErrorKind::InvalidData);
        assert!(closed_peer_observed(&far));
    }

    #[test]
    fn missing_descriptor_is_refused() {
        let (mut a, b) = UnixStream::pair().expect("stream pair");
        a.write_all(&[MARKER]).expect("write");
        assert_eq!(recv_fd(b.as_fd()).expect_err("no fd").kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn two_descriptors_are_refused_and_both_closed() {
        let (a, b) = UnixStream::pair().expect("stream pair");
        let (first, far_first) = UnixDatagram::pair().expect("first pair");
        let (second, far_second) = UnixDatagram::pair().expect("second pair");
        raw_send(&a, &[MARKER], &[first.as_fd(), second.as_fd()]);
        drop((first, second));
        assert_eq!(recv_fd(b.as_fd()).expect_err("two fds").kind(), io::ErrorKind::InvalidData);
        assert!(closed_peer_observed(&far_first));
        assert!(closed_peer_observed(&far_second));
    }

    #[test]
    fn closed_stream_reports_eof() {
        let (a, b) = UnixStream::pair().expect("stream pair");
        drop(a);
        assert_eq!(recv_fd(b.as_fd()).expect_err("eof").kind(), io::ErrorKind::UnexpectedEof);
    }

    #[test]
    fn empty_nonblocking_stream_would_block_and_keeps_state() {
        let (a, b) = UnixStream::pair().expect("stream pair");
        b.set_nonblocking(true).expect("nonblocking");
        assert_eq!(recv_fd(b.as_fd()).expect_err("empty").kind(), io::ErrorKind::WouldBlock);
        let (datagram, _far) = UnixDatagram::pair().expect("datagram pair");
        send_fd(a.as_fd(), datagram.as_fd()).expect("send");
        recv_fd(b.as_fd()).expect("receive after would-block");
        let mut rest = [0u8; 1];
        assert_eq!((&b).read(&mut rest).expect_err("nothing left").kind(), io::ErrorKind::WouldBlock);
    }

    #[test]
    fn only_a_unix_datagram_socket_qualifies() {
        let (datagram, _far) = UnixDatagram::pair().expect("datagram pair");
        let (stream, _far) = UnixStream::pair().expect("stream pair");
        let udp = std::net::UdpSocket::bind("127.0.0.1:0").expect("udp");
        assert!(is_unix_datagram(datagram.as_fd()).expect("datagram"));
        assert!(!is_unix_datagram(stream.as_fd()).expect("stream"));
        assert!(!is_unix_datagram(udp.as_fd()).expect("udp"));
    }

    fn raw_send(stream: &UnixStream, bytes: &[u8], fds: &[BorrowedFd<'_>]) {
        let mut space = [MaybeUninit::uninit(); cmsg_space!(ScmRights(4))];
        let mut control = SendAncillaryBuffer::new(&mut space);
        assert!(control.push(SendAncillaryMessage::ScmRights(fds)));
        sendmsg(stream, &[IoSlice::new(bytes)], &mut control, SendFlags::empty()).expect("raw sendmsg");
    }
}
