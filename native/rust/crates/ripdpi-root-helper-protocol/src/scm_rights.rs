use std::io;
use std::io::{IoSlice, IoSliceMut, Write};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, ControlMessageOwned, MsgFlags};

pub const MAX_MESSAGE_BYTES: usize = 8192;
const FRAME_HEADER_BYTES: usize = size_of::<u32>();
// Receive the whole supported AF_UNIX descriptor envelope before enforcing
// our one-fd contract. Linux/Android cap SCM_RIGHTS at 253 descriptors; XNU
// uses UIPC_MAX_CMSG_FD = 512 (bsd/kern/uipc_usrreq.c). These sockets do not
// enable optional ancillary-data producers. A two-fd buffer causes MSG_CTRUNC,
// which nix cannot iterate; XNU can also install fds omitted from that buffer.
// https://man7.org/linux/man-pages/man7/unix.7.html
// https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/uipc_usrreq.c
const MAX_ANCILLARY_FDS: usize = 512;

fn recv_flags() -> MsgFlags {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        MsgFlags::MSG_CMSG_CLOEXEC
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        MsgFlags::empty()
    }
}

/// Write one length-prefixed JSON frame, optionally sending one fd via
/// SCM_RIGHTS on the first bytes of the frame.
pub fn send_message(stream: &UnixStream, json: &[u8], fd: Option<BorrowedFd<'_>>) -> io::Result<()> {
    if json.len() > MAX_MESSAGE_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("root-helper IPC message exceeds {MAX_MESSAGE_BYTES} bytes"),
        ));
    }
    let length = u32::try_from(json.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "root-helper IPC message length does not fit u32"))?;
    let mut frame = Vec::with_capacity(FRAME_HEADER_BYTES + json.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(json);

    let iov = [IoSlice::new(&frame)];

    let sent = loop {
        let result = if let Some(fd) = fd {
            let fds = [fd.as_raw_fd()];
            let cmsg = [ControlMessage::ScmRights(&fds)];
            socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None)
        } else {
            socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &[], MsgFlags::empty(), None)
        };
        match result {
            Ok(sent) => break sent,
            Err(nix::errno::Errno::EINTR) => continue,
            Err(error) => return Err(io::Error::from(error)),
        }
    };
    if sent == 0 {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "root-helper IPC sendmsg wrote zero bytes"));
    }
    let mut writer = stream;
    writer.write_all(&frame[sent..])?;
    Ok(())
}

/// Read one length-prefixed JSON frame, optionally receiving one fd via
/// SCM_RIGHTS. Exact-size recvmsg loops preserve both stream frame boundaries
/// and ancillary data across arbitrary kernel short reads.
pub fn recv_message(stream: &UnixStream, eof_message: &'static str) -> io::Result<(Vec<u8>, Option<OwnedFd>)> {
    let fd = stream.as_raw_fd();
    let mut received_fds = Vec::new();
    let mut header = [0u8; FRAME_HEADER_BYTES];
    recv_exact_with_optional_fd(fd, &mut header, &mut received_fds, eof_message)?;

    let length = u32::from_be_bytes(header) as usize;
    if length > MAX_MESSAGE_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("root-helper IPC message exceeds {MAX_MESSAGE_BYTES} bytes"),
        ));
    }

    let mut payload = vec![0u8; length];
    recv_exact_with_optional_fd(fd, &mut payload, &mut received_fds, eof_message)?;
    let received_fd = received_fds.pop();
    Ok((payload, received_fd))
}

fn recv_exact_with_optional_fd(
    fd: RawFd,
    buf: &mut [u8],
    received_fds: &mut Vec<OwnedFd>,
    eof_message: &'static str,
) -> io::Result<()> {
    let mut offset = 0;
    while offset < buf.len() {
        let mut cmsg_buf = nix::cmsg_space!([RawFd; MAX_ANCILLARY_FDS]);
        let (bytes, mut chunk_fds) = {
            let mut iov = [IoSliceMut::new(&mut buf[offset..])];
            let msg = loop {
                match socket::recvmsg::<()>(fd, &mut iov, Some(&mut cmsg_buf), recv_flags()) {
                    Ok(msg) => break msg,
                    Err(nix::errno::Errno::EINTR) => continue,
                    Err(error) => return Err(io::Error::from(error)),
                }
            };
            if msg.flags.contains(MsgFlags::MSG_CTRUNC) {
                // SAFETY: recvmsg just filled this zero-initialized buffer.
                // No control-message iterator has adopted any returned fd.
                unsafe { close_truncated_scm_rights_fds(&cmsg_buf) };
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "root-helper IPC control message was truncated",
                ));
            }
            (msg.bytes, extract_scm_rights_fds(&msg)?)
        };
        received_fds.append(&mut chunk_fds);
        if bytes == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
        }
        if received_fds.len() > 1 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "root-helper IPC message carried more than one fd"));
        }
        offset += bytes;
    }
    Ok(())
}

fn extract_scm_rights_fds(msg: &socket::RecvMsg<'_, '_, ()>) -> io::Result<Vec<OwnedFd>> {
    let mut received_fds = Vec::new();
    for cmsg in msg.cmsgs().map_err(io::Error::from)? {
        if let ControlMessageOwned::ScmRights(fds) = cmsg {
            received_fds.extend(fds.into_iter().map(|fd| {
                // SAFETY: recvmsg installed each SCM_RIGHTS descriptor as a
                // fresh descriptor owned by this process. OwnedFd adopts that
                // unique ownership immediately so every later error closes it.
                unsafe { OwnedFd::from_raw_fd(fd) }
            }));
        }
    }
    Ok(received_fds)
}

/// Close the installed descriptor prefix when nix cannot iterate MSG_CTRUNC.
///
/// # Safety
/// `control` must be the zero-initialized ancillary buffer from a successful
/// recvmsg on a UnixStream. Every returned SCM_RIGHTS fd must remain uniquely
/// owned by this buffer; no iterator or other code may have adopted it.
unsafe fn close_truncated_scm_rights_fds(mut control: &[u8]) {
    let header_bytes = socket::cmsg_space::<()>();
    while control.len() >= header_bytes {
        // SAFETY: CMSG_SPACE(0) covers a complete initialized cmsghdr. The
        // kernel byte buffer has no alignment guarantee, so copy unaligned.
        let header = unsafe { control.as_ptr().cast::<libc::cmsghdr>().read_unaligned() };
        #[cfg(any(target_os = "android", target_os = "linux"))]
        let message_bytes = header.cmsg_len;
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        let message_bytes = header.cmsg_len as usize;
        if message_bytes < header_bytes || message_bytes > control.len() {
            break; // includes zero-initialized spare space after the prefix
        }
        let payload = &control[header_bytes..message_bytes];
        if header.cmsg_level == libc::SOL_SOCKET && header.cmsg_type == libc::SCM_RIGHTS {
            let (descriptors, remainder) = payload.as_chunks::<{ size_of::<RawFd>() }>();
            if !remainder.is_empty() {
                break;
            }
            for bytes in descriptors {
                let fd = RawFd::from_ne_bytes(*bytes);
                if fd < 0 {
                    return;
                }
                // SAFETY: the caller guarantees fresh kernel SCM_RIGHTS
                // output. Each complete descriptor is uniquely owned and
                // has not been adopted by nix or a previous iteration.
                drop(unsafe { OwnedFd::from_raw_fd(fd) });
            }
        }
        let Ok(payload_bytes) = u32::try_from(payload.len()) else {
            break;
        };
        // SAFETY: CMSG_SPACE only calculates native alignment. The payload
        // is bounded by the small receive buffer, so this cannot overflow.
        let next = unsafe { libc::CMSG_SPACE(payload_bytes) } as usize;
        let Some(remaining) = control.get(next..) else {
            break;
        };
        control = remaining;
    }
}

#[cfg(test)]
mod tests {
    use std::io::{IoSlice, Write};
    use std::os::fd::{AsFd, AsRawFd};
    use std::os::unix::net::UnixStream;

    use super::{MAX_MESSAGE_BYTES, recv_message, send_message};

    fn framed(payload: &[u8]) -> Vec<u8> {
        let length = u32::try_from(payload.len()).expect("test payload length").to_be_bytes();
        [length.as_slice(), payload].concat()
    }

    #[test]
    fn send_and_recv_message_transfers_json_frame_and_fd() {
        let (sender, receiver) = UnixStream::pair().expect("socket pair");
        send_message(&sender, br#"{"ok":true}"#, Some(sender.as_fd())).expect("send message");
        let (payload, received_fd) = recv_message(&receiver, "closed").expect("recv message");

        assert_eq!(payload, br#"{"ok":true}"#);
        let received_fd = received_fd.expect("received fd");

        assert!(received_fd.as_raw_fd() >= 0);
    }

    #[test]
    fn send_message_rejects_oversized_json() {
        let (sender, _receiver) = UnixStream::pair().expect("socket pair");
        let oversized = vec![b'a'; MAX_MESSAGE_BYTES + 1];

        let err = send_message(&sender, &oversized, None).expect_err("oversized send");

        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn recv_message_preserves_coalesced_stream_frames() {
        let (mut sender, receiver) = UnixStream::pair().expect("socket pair");
        let first = framed(br#"{"sequence":1}"#);
        let second = framed(br#"{"sequence":2}"#);
        sender.write_all(&[first, second].concat()).expect("write coalesced frames");

        let (first_payload, first_fd) = recv_message(&receiver, "closed").expect("first frame");
        assert_eq!(first_payload, br#"{"sequence":1}"#);
        assert!(first_fd.is_none());

        let (second_payload, second_fd) = recv_message(&receiver, "closed").expect("second frame");
        assert_eq!(second_payload, br#"{"sequence":2}"#);
        assert!(second_fd.is_none());
    }

    #[test]
    fn recv_message_collects_fragmented_header_without_losing_fd() {
        let (mut sender, receiver) = UnixStream::pair().expect("socket pair");
        let passed = std::fs::File::open("/dev/null").expect("open passed fd");
        let frame = framed(br#"{"fragmented":true}"#);
        let fds = [passed.as_raw_fd()];
        let cmsg = [nix::sys::socket::ControlMessage::ScmRights(&fds)];

        nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&frame[..1])],
            &cmsg,
            nix::sys::socket::MsgFlags::empty(),
            None,
        )
        .expect("send first header byte with fd");
        sender.write_all(&frame[1..]).expect("write remaining fragmented frame");

        let (payload, received_fd) = recv_message(&receiver, "closed").expect("fragmented frame");
        let received_fd = received_fd.expect("SCM_RIGHTS fd must survive fragmented header");

        assert_eq!(payload, br#"{"fragmented":true}"#);
        assert!(received_fd.as_raw_fd() >= 0);
    }

    #[test]
    fn recv_message_collects_fd_attached_to_payload_fragment() {
        let (mut sender, receiver) = UnixStream::pair().expect("socket pair");
        let passed = std::fs::File::open("/dev/null").expect("open passed fd");
        let payload = br#"{"payload_fd":true}"#;
        let header = u32::try_from(payload.len()).expect("payload length").to_be_bytes();
        sender.write_all(&header).expect("write header without fd");

        let fds = [passed.as_raw_fd()];
        let cmsg = [nix::sys::socket::ControlMessage::ScmRights(&fds)];
        nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(payload)],
            &cmsg,
            nix::sys::socket::MsgFlags::empty(),
            None,
        )
        .expect("send payload fragment with fd");

        let (received_payload, received_fd) = recv_message(&receiver, "closed").expect("fragmented frame");
        let received_fd = received_fd.expect("fd attached to payload must not be discarded");

        assert_eq!(received_payload, payload);
        assert!(received_fd.as_raw_fd() >= 0);
    }

    #[test]
    fn recv_message_rejects_oversized_frame_length() {
        let (mut sender, receiver) = UnixStream::pair().expect("socket pair");
        let oversized = u32::try_from(MAX_MESSAGE_BYTES + 1).expect("oversized test length").to_be_bytes();
        sender.write_all(&oversized).expect("write oversized frame header");

        let err = recv_message(&receiver, "closed").expect_err("oversized frame must fail");

        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    }

    #[test]
    fn recv_message_rejects_multiple_scm_rights_fds() {
        let (sender, receiver) = UnixStream::pair().expect("socket pair");
        let first = std::fs::File::open("/dev/null").expect("open first fd");
        let second = std::fs::File::open("/dev/null").expect("open second fd");
        let fds = [first.as_raw_fd(), second.as_raw_fd()];
        let cmsg = [nix::sys::socket::ControlMessage::ScmRights(&fds)];

        nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&framed(br#"{"ok":true}"#))],
            &cmsg,
            nix::sys::socket::MsgFlags::empty(),
            None,
        )
        .expect("raw send with two fds");

        let err = recv_message(&receiver, "closed").expect_err("multi-fd receive must fail");

        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("more than one fd"), "expected multi-fd rejection, got {err:?}",);
        assert!(first.as_raw_fd() >= 0);
        assert!(second.as_raw_fd() >= 0);
    }

    #[test]
    fn oversized_scm_rights_closes_every_received_fd() {
        assert_rejected_descriptors_are_closed(4);
        assert_rejected_descriptors_are_closed(253);
        #[cfg(target_os = "macos")]
        assert_rejected_descriptors_are_closed(254);
    }

    fn assert_rejected_descriptors_are_closed(descriptor_count: usize) {
        use std::io::Read;
        use std::time::Duration;

        let (sender, receiver) = UnixStream::pair().expect("IPC socket pair");
        let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
        peer.set_read_timeout(Some(Duration::from_millis(100))).expect("read timeout");
        // This exceeds the old two-fd receive buffer. EOF on the other
        // socket end proves that every transferred copy was closed.
        let fds = vec![passed.as_raw_fd(); descriptor_count];
        let control = [nix::sys::socket::ControlMessage::ScmRights(&fds)];
        nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&framed(br#"{"ok":true}"#))],
            &control,
            nix::sys::socket::MsgFlags::empty(),
            None,
        )
        .expect("send oversized descriptor array");
        drop(passed);

        let rejection = recv_message(&receiver, "closed");
        drop(receiver);
        let error = rejection.expect_err("multiple descriptors must be rejected");
        assert!(error.to_string().contains("more than one fd"), "{error}");
        assert_eq!(peer.read(&mut [0u8; 1]).expect("all transferred descriptors must close"), 0);
    }

    #[test]
    fn kernel_rejects_descriptor_array_larger_than_receive_envelope() {
        use std::io::Read;
        use std::time::Duration;

        let (sender, receiver) = UnixStream::pair().expect("IPC socket pair");
        let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
        peer.set_read_timeout(Some(Duration::from_millis(100))).expect("read timeout");
        let fds = vec![passed.as_raw_fd(); super::MAX_ANCILLARY_FDS + 1];
        let result = nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&framed(br#"{"ok":true}"#))],
            &[nix::sys::socket::ControlMessage::ScmRights(&fds)],
            nix::sys::socket::MsgFlags::empty(),
            None,
        );
        assert!(result.is_err(), "kernel accepted more descriptors than the receive envelope");
        drop(passed);
        drop(sender);
        drop(receiver);
        assert_eq!(peer.read(&mut [0u8; 1]).expect("kernel must retain no descriptors"), 0);
    }

    #[test]
    fn truncated_prefix_cleanup_closes_kernel_installed_fd() {
        use std::io::{IoSliceMut, Read};
        use std::time::Duration;

        let (sender, receiver) = UnixStream::pair().expect("IPC socket pair");
        let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
        peer.set_read_timeout(Some(Duration::from_millis(100))).expect("read timeout");
        send_message(&sender, b"{}", Some(passed.as_fd())).expect("send descriptor");
        drop(passed);
        let mut control = nix::cmsg_space!([std::os::fd::RawFd; super::MAX_ANCILLARY_FDS]);
        let mut header = [0u8; 4];
        let mut iov = [IoSliceMut::new(&mut header)];
        let message =
            nix::sys::socket::recvmsg::<()>(receiver.as_raw_fd(), &mut iov, Some(&mut control), super::recv_flags())
                .expect("capture kernel ancillary prefix");
        assert!(!message.flags.contains(nix::sys::socket::MsgFlags::MSG_CTRUNC));
        // Linux returns this same complete prefix plus zero spare space when
        // RLIMIT_NOFILE stops a later fd installation and sets MSG_CTRUNC.
        // SAFETY: this is fresh kernel output, with its descriptor still
        // unadopted; the nix control iterator has not been used.
        unsafe { super::close_truncated_scm_rights_fds(&control) };
        drop(receiver);
        assert_eq!(peer.read(&mut [0u8; 1]).expect("installed prefix must close"), 0);
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    #[test]
    fn receive_limit_truncation_closes_installed_prefix() {
        use std::io::Read;
        use std::time::Duration;

        const CHILD_ENV: &str = "RIPDPI_ROOT_FD_LIMIT_CHILD";
        if std::env::var_os(CHILD_ENV).is_none() {
            let result = std::process::Command::new(std::env::current_exe().expect("test executable"))
                .args([
                    "--exact",
                    "scm_rights::tests::receive_limit_truncation_closes_installed_prefix",
                    "--test-threads=1",
                ])
                .env(CHILD_ENV, "1")
                .output()
                .expect("run isolated fd-limit regression");
            assert!(
                result.status.success(),
                "stdout: {}\nstderr: {}",
                String::from_utf8_lossy(&result.stdout),
                String::from_utf8_lossy(&result.stderr),
            );
            return;
        }
        let (sender, receiver) = UnixStream::pair().expect("IPC socket pair");
        let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
        peer.set_read_timeout(Some(Duration::from_millis(100))).expect("read timeout");
        let fds = [passed.as_raw_fd(); 4];
        nix::sys::socket::sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&framed(b"{}"))],
            &[nix::sys::socket::ControlMessage::ScmRights(&fds)],
            nix::sys::socket::MsgFlags::empty(),
            None,
        )
        .expect("queue four descriptors before limiting receiver");
        drop(passed);

        let mut original = libc::rlimit { rlim_cur: 0, rlim_max: 0 };
        // SAFETY: original is a writable, initialized rlimit. This branch
        // runs only in a dedicated test subprocess with one selected test.
        assert_eq!(unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut original) }, 0);
        let limited = libc::rlimit { rlim_cur: 32.min(original.rlim_cur), rlim_max: original.rlim_max };
        // SAFETY: limited is valid; only the isolated child's soft limit changes.
        assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limited) }, 0);
        let mut fillers = Vec::new();
        while let Ok(file) = std::fs::File::open("/dev/null") {
            fillers.push(file);
        }
        assert!(fillers.len() >= 2, "subprocess needs two fd slots for the partial receive");
        drop(fillers.pop());
        drop(fillers.pop());
        // Linux installs two fds, then sets MSG_CTRUNC when the other two
        // cannot fit RLIMIT_NOFILE, even with the full ancillary buffer.
        let rejection = recv_message(&receiver, "closed");
        // SAFETY: original came from getrlimit in this isolated subprocess;
        // the unchanged hard limit permits restoration of its old soft limit.
        assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &original) }, 0);
        drop(fillers);
        drop(receiver);
        let error = rejection.expect_err("partial descriptor install must fail");
        assert!(error.to_string().contains("control message was truncated"), "{error}");
        assert_eq!(peer.read(&mut [0u8; 1]).expect("installed prefix must close"), 0);
    }
}
