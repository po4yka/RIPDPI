use std::io;

/// Protect a socket from being routed through the Android VPN tunnel.
///
/// On Android, VPN apps must "protect" outgoing sockets so traffic does not
/// loop back through the TUN interface. This sends the socket FD to the Java
/// VpnService via a Unix socket at `path`, which calls `VpnService.protect()`.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{sendmsg, ControlMessage, MsgFlags};
    use std::io::IoSlice;
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(Duration::from_secs(1)))?;
    stream.set_write_timeout(Some(Duration::from_secs(1)))?;

    let payload = [b'1'];
    let iov = [IoSlice::new(&payload)];
    let fd = socket.as_raw_fd();
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
    Ok(())
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "socket protection is only supported on Linux/Android"))
}

#[cfg(test)]
mod tests {
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn protect_socket_reports_unsupported_off_android() {
        let err =
            super::protect_socket(&(), "/tmp/ripdpi-protect.sock").expect_err("protect socket should be unsupported");

        assert_eq!(err.kind(), std::io::ErrorKind::Unsupported);
        assert_eq!(err.to_string(), "socket protection is only supported on Linux/Android");
    }
}
