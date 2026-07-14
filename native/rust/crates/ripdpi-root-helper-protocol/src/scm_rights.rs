use std::io;
use std::io::{IoSlice, IoSliceMut, Write};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, ControlMessageOwned, MsgFlags};

pub const MAX_MESSAGE_BYTES: usize = 8192;
const FRAME_HEADER_BYTES: usize = size_of::<u32>();

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
        let mut cmsg_buf = nix::cmsg_space!([RawFd; 2]);
        let (bytes, mut chunk_fds) = {
            let mut iov = [IoSliceMut::new(&mut buf[offset..])];
            let msg = loop {
                match socket::recvmsg::<()>(fd, &mut iov, Some(&mut cmsg_buf), recv_flags()) {
                    Ok(msg) => break msg,
                    Err(nix::errno::Errno::EINTR) => continue,
                    Err(error) => return Err(io::Error::from(error)),
                }
            };
            if msg.bytes == 0 {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
            }
            (msg.bytes, extract_scm_rights_fds(&msg)?)
        };
        received_fds.append(&mut chunk_fds);
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
    if msg.flags.contains(MsgFlags::MSG_CTRUNC) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "root-helper IPC control message was truncated"));
    }
    Ok(received_fds)
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
}
