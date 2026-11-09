use std::io;
use std::os::fd::RawFd;

use nix::sys::socket::{AddressFamily, SockaddrLike, SockaddrStorage, getpeername, getsockname};
use ripdpi_root_helper_protocol::CMD_SEND_IP_FRAGMENTED_UDP;

pub(super) fn validate_socket_fd(fd: RawFd, command: &str) -> io::Result<()> {
    let udp = command == CMD_SEND_IP_FRAGMENTED_UDP;
    let expected_type = if udp { libc::SOCK_DGRAM } else { libc::SOCK_STREAM };
    if socket_option(fd, libc::SO_TYPE)? != expected_type {
        return Err(invalid_socket());
    }
    let address: SockaddrStorage = getsockname(fd)?;
    if !matches!(address.family(), Some(AddressFamily::Inet | AddressFamily::Inet6)) {
        return Err(invalid_socket());
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let expected_protocol = if udp { libc::IPPROTO_UDP } else { libc::IPPROTO_TCP };
        if socket_option(fd, libc::SO_PROTOCOL)? != expected_protocol {
            return Err(invalid_socket());
        }
    }
    if !udp {
        let _: SockaddrStorage = getpeername(fd)?;
    }
    Ok(())
}

fn invalid_socket() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "root-helper command requires a matching IPv4/IPv6 socket")
}

fn socket_option(fd: RawFd, option: libc::c_int) -> io::Result<libc::c_int> {
    let mut value: libc::c_int = 0;
    let mut length = size_of::<libc::c_int>() as libc::socklen_t;
    // SAFETY: value and length are initialized writable storage of the supplied
    // sizes. getsockopt checks the raw fd and returns EBADF for an invalid one.
    let result = unsafe { libc::getsockopt(fd, libc::SOL_SOCKET, option, (&raw mut value).cast(), &raw mut length) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    if length as usize != size_of::<libc::c_int>() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socket option length"));
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use std::io::Read;
    use std::net::{TcpListener, TcpStream, UdpSocket};
    use std::os::fd::{AsRawFd, FromRawFd, IntoRawFd, OwnedFd};
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    use nix::sys::socket::{AddressFamily, SockFlag, SockType, socket};

    use ripdpi_root_helper_protocol::{CMD_SEND_ORDERED_TCP_SEGMENTS, HelperRequest, PROTOCOL_VERSION};

    #[test]
    fn regular_file_cannot_be_adopted_as_a_tcp_socket() {
        let file = std::fs::File::open("/dev/null").expect("file");
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_ORDERED_TCP_SEGMENTS.into(),
            params: serde_json::json!({"segments": [], "original_payload_len": 0, "default_ttl": 64}),
            session_nonce: None,
        };
        let outcome = super::super::dispatch_command(&request, Some(file.into_raw_fd()));
        // SAFETY: a successful handler transfers sole ownership of this reply descriptor.
        let returned = outcome.reply_fd.map(|fd| unsafe { OwnedFd::from_raw_fd(fd) });
        drop(returned);
        assert!(!outcome.response.ok, "a file is not a connected TCP socket");
        assert!(outcome.reply_fd.is_none(), "invalid input cannot return a socket");
    }

    #[test]
    fn wrong_or_unconnected_tcp_sockets_are_rejected_before_adoption() {
        let udp = UdpSocket::bind("127.0.0.1:0").expect("UDP socket");
        #[cfg(any(target_os = "linux", target_os = "android"))]
        let flags = SockFlag::SOCK_CLOEXEC;
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        let flags = SockFlag::empty();
        let unconnected = socket(AddressFamily::Inet, SockType::Stream, flags, None).expect("unconnected TCP socket");
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        nix::fcntl::fcntl(&unconnected, nix::fcntl::FcntlArg::F_SETFD(nix::fcntl::FdFlag::FD_CLOEXEC))
            .expect("close unconnected socket on exec");
        let (unix, mut peer) = UnixStream::pair().expect("Unix socket pair");
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_ORDERED_TCP_SEGMENTS.into(),
            params: serde_json::json!({"segments": [], "original_payload_len": 0, "default_ttl": 64}),
            session_nonce: None,
        };
        for fd in [udp.into_raw_fd(), unconnected.into_raw_fd(), unix.into_raw_fd()] {
            let outcome = super::super::dispatch_command(&request, Some(fd));
            // SAFETY: dispatch transfers any reply descriptor to this test exactly once.
            drop(outcome.reply_fd.map(|fd| unsafe { OwnedFd::from_raw_fd(fd) }));
            assert!(!outcome.response.ok, "a TCP command needs a connected Internet TCP socket");
            assert!(outcome.reply_fd.is_none());
        }
        assert_eq!(peer.read(&mut [0_u8]).expect("rejected Unix socket must close"), 0);
    }

    #[test]
    fn connected_ipv4_and_ipv6_tcp_and_udp_are_accepted_for_their_commands() {
        for address in ["127.0.0.1:0", "[::1]:0"] {
            let listener = TcpListener::bind(address).expect("TCP listener");
            let tcp = TcpStream::connect(listener.local_addr().expect("listener address")).expect("TCP stream");
            let _peer = listener.accept().expect("TCP peer");
            let udp = UdpSocket::bind(address).expect("UDP socket");

            super::validate_socket_fd(tcp.as_raw_fd(), CMD_SEND_ORDERED_TCP_SEGMENTS).expect("connected TCP");
            super::validate_socket_fd(udp.as_raw_fd(), ripdpi_root_helper_protocol::CMD_SEND_IP_FRAGMENTED_UDP)
                .expect("unconnected UDP");
            assert!(
                super::validate_socket_fd(tcp.as_raw_fd(), ripdpi_root_helper_protocol::CMD_SEND_IP_FRAGMENTED_UDP)
                    .is_err()
            );
            assert!(super::validate_socket_fd(udp.as_raw_fd(), CMD_SEND_ORDERED_TCP_SEGMENTS).is_err());
        }
    }
}
