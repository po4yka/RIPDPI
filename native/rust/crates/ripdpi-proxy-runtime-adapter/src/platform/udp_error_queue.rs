//! Explicit, bounded UDP ICMP feedback from the Linux socket error queue.

use std::io;
use std::net::{IpAddr, SocketAddr, UdpSocket};

/// One remote ICMP error. The payload is a prefix of the original UDP payload,
/// not an IP packet. An empty prefix is valid and can make correlation ambiguous.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RemoteIcmpError {
    pub target: SocketAddr,
    pub source: IpAddr,
    pub icmp_type: u8,
    pub icmp_code: u8,
    pub info: u32,
    pub payload_prefix: Vec<u8>,
}

pub const fn supported() -> bool {
    cfg!(any(target_os = "linux", target_os = "android"))
}

/// Enables feedback on this socket only. The caller must enable it before sending.
pub fn enable(socket: &UdpSocket) -> io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        linux::enable(socket)
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = socket;
        Err(io::Error::new(io::ErrorKind::Unsupported, "UDP ICMP error queues are unavailable"))
    }
}

/// Consumes at most 64 queued events without blocking. Local, unsupported, and
/// truncated events are discarded. The caller must correlate each returned prefix
/// with an unambiguous real upstream send before it reports an error to a client.
pub fn drain(socket: &UdpSocket) -> io::Result<Vec<RemoteIcmpError>> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        linux::drain(socket)
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = socket;
        Err(io::Error::new(io::ErrorKind::Unsupported, "UDP ICMP error queues are unavailable"))
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
mod linux {
    use std::io::IoSliceMut;
    use std::net::{Ipv4Addr, Ipv6Addr};
    use std::os::fd::AsRawFd;

    use nix::errno::Errno;
    use nix::sys::socket::{ControlMessageOwned, MsgFlags, SockaddrStorage, recvmsg, setsockopt, sockopt};

    use super::*;

    const MAX_EVENTS: usize = 64;
    const MAX_PAYLOAD: usize = 1232;

    pub(super) fn enable(socket: &UdpSocket) -> io::Result<()> {
        if socket.local_addr()?.is_ipv4() {
            setsockopt(socket, sockopt::Ipv4RecvErr, &true)?;
        } else {
            setsockopt(socket, sockopt::Ipv6RecvErr, &true)?;
        }
        Ok(())
    }

    pub(super) fn drain(socket: &UdpSocket) -> io::Result<Vec<RemoteIcmpError>> {
        let ipv4 = socket.local_addr()?.is_ipv4();
        let mut events = Vec::new();
        let mut payload = [0; MAX_PAYLOAD];
        // nix owns the aligned ancillary allocation and validates MSG_CTRUNC.
        let mut control = nix::cmsg_space!(libc::sock_extended_err, libc::sockaddr_storage);
        for _ in 0..MAX_EVENTS {
            let mut iov = [IoSliceMut::new(&mut payload)];
            let message = match recvmsg::<SockaddrStorage>(
                socket.as_raw_fd(),
                &mut iov,
                Some(&mut control),
                MsgFlags::MSG_ERRQUEUE | MsgFlags::MSG_DONTWAIT,
            ) {
                Ok(message) => message,
                Err(Errno::EAGAIN) => break,
                Err(Errno::EINTR) => continue,
                Err(error) => return Err(error.into()),
            };
            if message.flags.intersects(MsgFlags::MSG_TRUNC | MsgFlags::MSG_CTRUNC) {
                continue;
            }
            let target = message.address.and_then(|address| {
                if ipv4 {
                    address.as_sockaddr_in().copied().map(SocketAddr::from)
                } else {
                    address.as_sockaddr_in6().copied().map(SocketAddr::from)
                }
            });
            let Some(target) = target else { continue };
            let length = message.bytes;
            let Ok(messages) = message.cmsgs() else { continue };
            let mut remote = None;
            let mut duplicate = false;
            for ancillary in messages {
                let decoded = match ancillary {
                    ControlMessageOwned::Ipv4RecvErr(error, Some(source))
                        if ipv4 && i32::from(source.sin_family) == libc::AF_INET =>
                    {
                        let source = IpAddr::V4(Ipv4Addr::from(source.sin_addr.s_addr.to_ne_bytes()));
                        decode(error, source, target)
                    }
                    ControlMessageOwned::Ipv6RecvErr(error, Some(source))
                        if !ipv4 && i32::from(source.sin6_family) == libc::AF_INET6 =>
                    {
                        decode(error, IpAddr::V6(Ipv6Addr::from(source.sin6_addr.s6_addr)), target)
                    }
                    _ => None,
                };
                if let Some(error) = decoded {
                    duplicate |= remote.is_some();
                    remote = Some(error);
                }
            }
            if !duplicate && let Some(mut remote) = remote {
                remote.payload_prefix.extend_from_slice(&payload[..length]);
                events.push(remote);
            }
        }
        Ok(events)
    }

    fn decode(error: libc::sock_extended_err, source: IpAddr, target: SocketAddr) -> Option<RemoteIcmpError> {
        let valid = match (source, target) {
            (IpAddr::V4(source), SocketAddr::V4(_)) => {
                error.ee_origin == libc::SO_EE_ORIGIN_ICMP
                    && !source.is_unspecified()
                    && !source.is_multicast()
                    && !source.is_broadcast()
                    && matches!((error.ee_type, error.ee_code), (3, 0..=15) | (11, 0))
            }
            (IpAddr::V6(source), SocketAddr::V6(_)) => {
                error.ee_origin == libc::SO_EE_ORIGIN_ICMP6
                    && !source.is_unspecified()
                    && !source.is_multicast()
                    && matches!((error.ee_type, error.ee_code), (1, 0..=6) | (2, 0) | (3, 0..=1))
            }
            _ => false,
        };
        valid.then(|| RemoteIcmpError {
            target,
            source,
            icmp_type: error.ee_type,
            icmp_code: error.ee_code,
            info: error.ee_info,
            payload_prefix: Vec::new(),
        })
    }

    #[cfg(test)]
    mod tests {
        use std::time::{Duration, Instant};

        use super::*;

        fn collect(socket: &UdpSocket) -> Vec<RemoteIcmpError> {
            let deadline = Instant::now() + Duration::from_secs(2);
            loop {
                let errors = drain(socket).unwrap();
                if !errors.is_empty() {
                    return errors;
                }
                assert!(Instant::now() < deadline, "kernel did not return loopback ICMP");
                std::thread::sleep(Duration::from_millis(2));
            }
        }

        fn closed_port_feedback(bind: &str, expected_type: u8, expected_code: u8) {
            let receiver = UdpSocket::bind(bind).unwrap();
            let target = receiver.local_addr().unwrap();
            // A connected, live local socket returns no queued error.
            let sender = UdpSocket::bind(bind).unwrap();
            sender.connect(target).unwrap();
            enable(&sender).unwrap();
            assert!(drain(&sender).unwrap().is_empty());
            drop(receiver);
            for marker in [b"first".as_slice(), b"second".as_slice()] {
                assert_eq!(sender.send(marker).unwrap(), marker.len());
                let errors = collect(&sender);
                assert_eq!(errors.len(), 1);
                assert_eq!(errors[0].target, target);
                assert_eq!(errors[0].source, target.ip());
                assert_eq!(errors[0].icmp_type, expected_type);
                assert_eq!(errors[0].icmp_code, expected_code);
                assert_eq!(errors[0].info, 0);
                assert_eq!(errors[0].payload_prefix, marker);
                assert!(drain(&sender).unwrap().is_empty());
            }
            // A zero-byte quote is an event, not EOF.
            sender.send(&[]).unwrap();
            assert!(collect(&sender)[0].payload_prefix.is_empty());
        }

        #[test]
        fn kernel_ipv4_port_unreachable_and_draining() {
            closed_port_feedback("127.0.0.1:0", 3, 3);
        }

        #[test]
        fn kernel_ipv6_port_unreachable_and_draining() {
            closed_port_feedback("[::1]:0", 1, 4);
        }

        #[test]
        fn rejects_local_unknown_and_wrong_family_errors() {
            let target: SocketAddr = "127.0.0.1:9".parse().unwrap();
            let mut error = libc::sock_extended_err {
                ee_errno: libc::ECONNREFUSED as u32,
                ee_origin: libc::SO_EE_ORIGIN_LOCAL,
                ee_type: 3,
                ee_code: 3,
                ee_pad: 0,
                ee_info: 0,
                ee_data: 0,
            };
            assert!(decode(error, target.ip(), target).is_none());
            error.ee_origin = libc::SO_EE_ORIGIN_ICMP;
            assert!(decode(error, target.ip(), target).is_some());
            assert!(decode(error, IpAddr::V6(Ipv6Addr::LOCALHOST), target).is_none());
            assert!(decode(error, IpAddr::V4(Ipv4Addr::UNSPECIFIED), target).is_none());
            error.ee_type = 12; // Pointer translation from upstream headers is not available.
            assert!(decode(error, target.ip(), target).is_none());
            error.ee_type = 11;
            error.ee_code = 1;
            assert!(decode(error, target.ip(), target).is_none());
        }
    }
}

#[cfg(all(test, not(any(target_os = "linux", target_os = "android"))))]
mod tests {
    use super::*;

    #[test]
    fn unsupported_platform_is_explicit() {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        assert!(!supported());
        assert_eq!(enable(&socket).unwrap_err().kind(), io::ErrorKind::Unsupported);
        assert_eq!(drain(&socket).unwrap_err().kind(), io::ErrorKind::Unsupported);
    }
}
