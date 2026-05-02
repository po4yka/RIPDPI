use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, MsgFlags};

/// Write a JSON-line message, optionally sending one fd via SCM_RIGHTS.
pub fn send_message(stream: &UnixStream, json: &[u8], fd: Option<RawFd>) -> io::Result<()> {
    use std::io::IoSlice;

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
    let mut cmsg_buf = [0u8; 64];
    recv_line_with_optional_fd(stream.as_raw_fd(), &mut buf, &mut cmsg_buf, eof_message)
}

fn recv_line_with_optional_fd(
    fd: RawFd,
    buf: &mut [u8],
    cmsg_buf: &mut [u8],
    eof_message: &'static str,
) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };

    // SAFETY: zeroed bytes are a valid initial state for `msghdr` before the
    // pointer fields are explicitly populated below.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast();
    msg.msg_controllen = cmsg_buf.len() as _;

    // SAFETY: `msg` points at live caller-owned iov and control buffers for the
    // duration of this syscall.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
    }

    let data = &buf[..n as usize];
    let data = data.strip_suffix(b"\n").unwrap_or(data);
    Ok((data.to_vec(), extract_scm_rights_fd(&msg)))
}

fn extract_scm_rights_fd(msg: &libc::msghdr) -> Option<RawFd> {
    // SAFETY: `msg` must describe a control buffer populated by `recvmsg`; the
    // caller owns the underlying storage for the duration of this traversal.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(msg) };
    while !cmsg.is_null() {
        // SAFETY: `cmsg` is either null or a valid control header pointer from
        // the `CMSG_*` traversal macros.
        let hdr = unsafe { &*cmsg };
        if hdr.cmsg_level == libc::SOL_SOCKET && hdr.cmsg_type == libc::SCM_RIGHTS {
            // SAFETY: `SCM_RIGHTS` stores at least one `RawFd` in the
            // associated control message payload.
            let data_ptr = unsafe { libc::CMSG_DATA(cmsg) };
            // SAFETY: `data_ptr` points into the live ancillary buffer owned by
            // `msg`; SCM_RIGHTS stores at least one file descriptor payload.
            let data = unsafe { std::slice::from_raw_parts(data_ptr.cast::<u8>(), std::mem::size_of::<RawFd>()) };
            return read_unaligned_raw_fd(data);
        }
        // SAFETY: advances within the same ancillary buffer described by `msg`.
        cmsg = unsafe { libc::CMSG_NXTHDR(msg, cmsg) };
    }
    None
}

fn read_unaligned_raw_fd(bytes: &[u8]) -> Option<RawFd> {
    if bytes.len() < std::mem::size_of::<RawFd>() {
        return None;
    }
    // SAFETY: `bytes` is a valid slice; we read exactly `size_of::<RawFd>()`
    // bytes from its base pointer and `read_unaligned` permits any alignment.
    Some(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<RawFd>()) })
}

#[cfg(test)]
mod tests {
    use std::mem::{size_of, zeroed};
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::os::unix::net::UnixStream;
    use std::ptr;

    use super::{extract_scm_rights_fd, read_unaligned_raw_fd, recv_message, send_message};

    #[test]
    fn read_unaligned_raw_fd_reads_i32_payload() {
        let fd = 0x0102_0304_i32;
        let bytes = fd.to_ne_bytes();

        assert_eq!(read_unaligned_raw_fd(&bytes), Some(fd));
    }

    #[test]
    fn read_unaligned_raw_fd_rejects_short_payload() {
        assert_eq!(read_unaligned_raw_fd(&[1, 2, 3]), None);
    }

    #[test]
    fn extract_scm_rights_fd_returns_received_fd() {
        let control_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as _) } as usize;
        let mut control = vec![0u8; control_len];
        let mut msg: libc::msghdr = unsafe { zeroed() };
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = control_len as _;

        // SAFETY: `msg` describes the writable `control` buffer above.
        let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
        assert!(!cmsg.is_null());

        let expected_fd: libc::c_int = 123;
        unsafe {
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len = libc::CMSG_LEN(size_of::<libc::c_int>() as _) as _;
            ptr::write_unaligned(libc::CMSG_DATA(cmsg).cast(), expected_fd);
        }

        assert_eq!(extract_scm_rights_fd(&msg), Some(expected_fd));
    }

    #[test]
    fn extract_scm_rights_fd_ignores_non_fd_control_messages() {
        let control_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as _) } as usize;
        let mut control = vec![0u8; control_len];
        let mut msg: libc::msghdr = unsafe { zeroed() };
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = control_len as _;

        // SAFETY: `msg` describes the writable `control` buffer above.
        let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
        assert!(!cmsg.is_null());

        unsafe {
            (*cmsg).cmsg_level = libc::IPPROTO_IP;
            (*cmsg).cmsg_type = libc::IP_TTL;
            (*cmsg).cmsg_len = libc::CMSG_LEN(size_of::<libc::c_int>() as _) as _;
            ptr::write_unaligned(libc::CMSG_DATA(cmsg).cast(), 64 as libc::c_int);
        }

        assert_eq!(extract_scm_rights_fd(&msg), None);
    }

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
