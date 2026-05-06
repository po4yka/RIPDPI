use std::io;
use std::io::{IoSlice, IoSliceMut};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, ControlMessageOwned, MsgFlags};

/// Write a JSON-line message, optionally sending one fd via SCM_RIGHTS.
pub fn send_message(stream: &UnixStream, json: &[u8], fd: Option<RawFd>) -> io::Result<()> {
    let mut payload = Vec::with_capacity(json.len() + 1);
    payload.extend_from_slice(json);
    payload.push(b'\n');

    let iov = [IoSlice::new(&payload)];

    if let Some(fd) = fd {
        let fds = [fd];
        let cmsg = [ControlMessage::ScmRights(&fds)];
        socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;
    } else {
        socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &[], MsgFlags::empty(), None).map_err(io::Error::from)?;
    }
    Ok(())
}

/// Read a JSON-line message, optionally receiving one fd via SCM_RIGHTS.
pub fn recv_message(stream: &UnixStream, eof_message: &'static str) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut buf = [0u8; 8192];
    let mut cmsg_buf = nix::cmsg_space!([RawFd; 1]);
    recv_line_with_optional_fd(stream.as_raw_fd(), &mut buf, &mut cmsg_buf, eof_message)
}

fn recv_line_with_optional_fd(
    fd: RawFd,
    buf: &mut [u8],
    cmsg_buf: &mut [u8],
    eof_message: &'static str,
) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let (bytes, received_fd) = {
        let mut iov = [IoSliceMut::new(buf)];
        let msg = socket::recvmsg::<()>(fd, &mut iov, Some(cmsg_buf), MsgFlags::empty()).map_err(io::Error::from)?;
        if msg.bytes == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
        }

        (msg.bytes, extract_scm_rights_fd(&msg)?)
    };

    let data = &buf[..bytes];
    let data = data.strip_suffix(b"\n").unwrap_or(data);
    Ok((data.to_vec(), received_fd))
}

fn extract_scm_rights_fd(msg: &socket::RecvMsg<'_, '_, ()>) -> io::Result<Option<RawFd>> {
    for cmsg in msg.cmsgs().map_err(io::Error::from)? {
        if let ControlMessageOwned::ScmRights(fds) = cmsg {
            return Ok(fds.first().copied());
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::os::unix::net::UnixStream;

    use super::{recv_message, send_message};

    #[test]
    fn send_and_recv_message_transfers_json_line_and_fd() {
        let (sender, receiver) = UnixStream::pair().expect("socket pair");
        let fd = sender.as_raw_fd();

        send_message(&sender, br#"{"ok":true}"#, Some(fd)).expect("send message");
        let (payload, received_fd) = recv_message(&receiver, "closed").expect("recv message");

        assert_eq!(payload, br#"{"ok":true}"#);
        let received_fd = received_fd.expect("received fd");

        // SAFETY: ownership of this descriptor was transferred via SCM_RIGHTS;
        // wrapping it closes the duplicated fd at the end of the test.
        let owned_fd = unsafe { OwnedFd::from_raw_fd(received_fd) };
        assert!(owned_fd.as_raw_fd() >= 0);
    }
}
