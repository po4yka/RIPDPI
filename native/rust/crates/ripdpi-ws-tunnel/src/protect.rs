use std::io;

/// Protect a socket from being routed through the Android VPN tunnel.
///
/// On Android, VPN apps must "protect" outgoing sockets so traffic does not
/// loop back through the TUN interface. This sends the socket FD to the Java
/// VpnService via a Unix socket at `path`, which calls `VpnService.protect()`.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};
    use socket2::{Domain, SockAddr, Socket, Type};
    use std::io::IoSlice;
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    let channel = Socket::new(Domain::UNIX, Type::STREAM, None)?;
    channel.set_read_timeout(Some(Duration::from_secs(1)))?;
    // A full AF_UNIX accept queue can block connect, so set its timeout first.
    channel.set_write_timeout(Some(Duration::from_secs(1)))?;
    channel.connect(&SockAddr::unix(path)?)?;
    let stream = UnixStream::from(channel);

    let payload = *b"1";
    let iov = [IoSlice::new(&payload)];
    let fd = socket.as_raw_fd();
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
    // VpnProtectSocketServer writes 0 on success and 1 on denial; fail closed on
    // a non-zero ack so the caller never proceeds with an unprotected socket.
    if ack[0] != 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect denied the socket"));
    }
    Ok(())
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "socket protection is only supported on Linux/Android"))
}

#[cfg(test)]
mod tests {
    #[cfg(target_os = "linux")]
    #[test]
    fn protect_connect_times_out_when_the_accept_queue_is_full() {
        use std::os::unix::net::UnixStream;
        use std::sync::mpsc;
        use std::time::{Duration, Instant};

        use socket2::{Domain, SockAddr, Socket, Type};

        let path = std::env::temp_dir().join(format!("ripdpi-ws-protect-backlog-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let listener = Socket::new(Domain::UNIX, Type::STREAM, None).expect("UDS listener");
        listener.bind(&SockAddr::unix(&path).expect("UDS address")).expect("bind UDS");
        listener.listen(0).expect("listen without spare queue slots");
        let queued = UnixStream::connect(&path).expect("fill the accept queue");
        let (finished, result) = mpsc::channel();
        let worker_path = path.clone();
        let started = Instant::now();
        let worker = std::thread::spawn(move || {
            let outbound = Socket::new(Domain::IPV4, Type::STREAM, None).expect("outbound socket");
            let protected = super::protect_socket(&outbound, worker_path.to_str().expect("UTF-8 path"));
            let _ = finished.send(protected);
        });
        let observed = result.recv_timeout(Duration::from_millis(2_500));
        let elapsed = started.elapsed();
        drop(listener);
        drop(queued);
        worker.join().expect("protect worker exits after queue teardown");
        std::fs::remove_file(&path).expect("remove UDS path");

        let error = observed.expect("connect must finish before the deadline").expect_err("full queue cannot protect");
        assert!(matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut));
        assert!(elapsed < Duration::from_millis(2_500), "connect took {elapsed:?}");
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn protect_socket_reports_unsupported_off_android() {
        let err =
            super::protect_socket(&(), "/tmp/ripdpi-protect.sock").expect_err("protect socket should be unsupported");

        assert_eq!(err.kind(), std::io::ErrorKind::Unsupported);
        assert_eq!(err.to_string(), "socket protection is only supported on Linux/Android");
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn protect_socket_fails_when_unix_path_does_not_exist() {
        let sock = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind udp");
        let err = super::protect_socket(&sock, "/tmp/ripdpi-nonexistent-protect.sock")
            .expect_err("should fail on missing path");
        assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    }
}
