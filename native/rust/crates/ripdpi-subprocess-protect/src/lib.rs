//! `VpnService.protect` for RIPDPI subprocess helper binaries.
//!
//! The proxy/PT helper binaries (`ripdpi-naiveproxy`, `ripdpi-webtunnel`,
//! `ripdpi-cloudflare-origin`) run as *separate processes* spawned by the
//! Android app. They cannot use the in-process protect callback registry
//! (`ripdpi-native-protect`) — that lives in `libripdpi`'s address space. When
//! the app VPN is up, a helper's outbound socket to a non-loopback server is
//! captured by the app's own TUN route and loops, unless it is protected.
//!
//! Protection for a subprocess follows the shadowsocks-android pattern: the
//! helper passes its socket fd to the parent over a Unix domain socket using
//! `SCM_RIGHTS`; the parent (`VpnProtectSocketServer`) calls
//! `VpnService.protect(fd)` and writes a one-byte ack. This crate is the
//! subprocess-side client of that protocol, byte-compatible with the sender in
//! `ripdpi-ws-tunnel` and the server in Kotlin `VpnProtectSocketServer`.
//!
//! The parent supplies the UDS path via the [`PROTECT_PATH_ENV`] environment
//! variable, set only while the VPN is up. When it is unset/blank (desktop,
//! proxy-only mode, VPN down) the helpers connect unprotected, exactly as
//! before — so wiring this in never breaks the non-VPN paths.

#![forbid(unsafe_code)]

use std::io;
use std::net::SocketAddr;
use std::os::fd::{AsRawFd, RawFd};
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

/// Environment variable carrying the parent's `VpnService.protect` UDS path.
pub const PROTECT_PATH_ENV: &str = "RIPDPI_PROTECT_PATH";

/// Timeout for the local protect round-trip (UDS connect / send / ack read).
const PROTECT_TIMEOUT: Duration = Duration::from_secs(1);

/// The protect UDS path the Android parent supplies via [`PROTECT_PATH_ENV`],
/// or `None` when it is unset/blank — in which case callers connect
/// unprotected (desktop, proxy-only mode, or VPN down).
#[must_use]
pub fn protect_path_from_env() -> Option<String> {
    normalize_protect_path(std::env::var(PROTECT_PATH_ENV).ok())
}

/// Trim a raw path value and treat blank as absent.
fn normalize_protect_path(raw: Option<String>) -> Option<String> {
    raw.map(|value| value.trim().to_owned()).filter(|value| !value.is_empty())
}

/// Hand `fd` to the parent `VpnService` over the UDS at `path`: connect, send a
/// one-byte payload plus the fd via `SCM_RIGHTS`, then await the parent's
/// one-byte ack. Mirrors the wire contract served by Kotlin
/// `VpnProtectSocketServer` and the sender in `ripdpi-ws-tunnel`.
///
/// Call this on a still-unconnected socket, before `connect`. Synchronous and
/// fast (a local round-trip). On any failure the socket must not be used: the
/// callers below propagate the error instead of connecting unprotected.
pub fn protect_fd(fd: RawFd, path: &str) -> io::Result<()> {
    use std::io::{IoSlice, Read};
    use std::os::unix::net::UnixStream;

    use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};

    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(PROTECT_TIMEOUT))?;
    stream.set_write_timeout(Some(PROTECT_TIMEOUT))?;

    let iov = [IoSlice::new(b"1")];
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
    // The parent writes 0 on success and 1 on denial (VpnService.protect
    // returned false, fd extraction failed, or the request was rejected under
    // back-pressure/shutdown — see Kotlin VpnProtectSocketServer). Fail closed
    // on a non-zero ack so the caller abandons the still-unprotected socket
    // instead of connecting and looping into the TUN.
    if ack[0] != 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect denied the socket"));
    }
    Ok(())
}

/// Protect `fd` when a `protect_path` is configured and `addr` is non-loopback.
/// A no-op for loopback targets and when no path is configured.
fn protect_if_needed(fd: RawFd, addr: SocketAddr, protect_path: Option<&str>) -> io::Result<()> {
    match protect_path {
        Some(path) if !addr.ip().is_loopback() => protect_fd(fd, path),
        _ => Ok(()),
    }
}

/// Resolve `target`, then create a TCP socket, protect it (when `protect_path`
/// is set and the target is non-loopback), and connect — async (tokio).
/// Returns the first candidate that connects.
///
/// Drop-in for `tokio::net::TcpStream::connect(target)` that honors the
/// `VpnService.protect` invariant: the fd is protected *before* `connect`.
pub async fn protected_tcp_connect<A: tokio::net::ToSocketAddrs>(
    target: A,
    protect_path: Option<&str>,
) -> io::Result<tokio::net::TcpStream> {
    let mut last_err = None;
    for addr in tokio::net::lookup_host(target).await? {
        match connect_one_async(addr, protect_path).await {
            Ok(stream) => return Ok(stream),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no addresses resolved")))
}

async fn connect_one_async(addr: SocketAddr, protect_path: Option<&str>) -> io::Result<tokio::net::TcpStream> {
    let socket = match addr {
        SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4()?,
        SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6()?,
    };
    protect_if_needed(socket.as_raw_fd(), addr, protect_path)?;
    socket.connect(addr).await
}

/// Blocking (std) counterpart of [`protected_tcp_connect`] for callers not on a
/// tokio runtime. Drop-in for `std::net::TcpStream::connect(target)`.
pub fn protected_tcp_connect_blocking<A: std::net::ToSocketAddrs>(
    target: A,
    protect_path: Option<&str>,
) -> io::Result<std::net::TcpStream> {
    let mut last_err = None;
    for addr in target.to_socket_addrs()? {
        match connect_one_blocking(addr, protect_path) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no addresses resolved")))
}

fn connect_one_blocking(addr: SocketAddr, protect_path: Option<&str>) -> io::Result<std::net::TcpStream> {
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    protect_if_needed(socket.as_raw_fd(), addr, protect_path)?;
    socket.connect(&SockAddr::from(addr))?;
    Ok(socket.into())
}

#[cfg(test)]
mod tests {
    use std::io::{IoSliceMut, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::os::unix::net::{UnixListener, UnixStream};
    use std::thread;

    use nix::sys::socket::{ControlMessageOwned, MsgFlags, recvmsg};

    use super::*;

    /// One-shot mock of the Kotlin `VpnProtectSocketServer`: accept one client,
    /// receive its fd via `SCM_RIGHTS`, write the one-byte ack. Returns whether
    /// a valid fd was received.
    fn spawn_mock_protect_server(path: std::path::PathBuf, ack: u8) -> thread::JoinHandle<bool> {
        let listener = UnixListener::bind(&path).expect("bind mock protect uds");
        thread::spawn(move || {
            let (conn, _) = listener.accept().expect("accept protect client");
            let mut buf = [0u8; 1];
            let mut cmsg_space = nix::cmsg_space!([RawFd; 1]);
            let cmsg_buf: &mut [u8] = &mut cmsg_space;
            let mut iov = [IoSliceMut::new(&mut buf)];
            let msg = recvmsg::<()>(conn.as_raw_fd(), &mut iov, Some(cmsg_buf), MsgFlags::empty())
                .expect("recvmsg from protect client");
            let mut got_fd = false;
            for cmsg in msg.cmsgs().expect("cmsgs") {
                if let ControlMessageOwned::ScmRights(fds) = cmsg {
                    // Received descriptors are kernel dups; the test process
                    // exits promptly, so they are intentionally not closed
                    // (closing would need `unsafe` in this forbid-unsafe crate).
                    got_fd = !fds.is_empty() && fds.iter().all(|fd| *fd >= 0);
                }
            }
            (&conn).write_all(&[ack]).expect("write ack");
            got_fd
        })
    }

    #[test]
    fn normalize_protect_path_trims_and_filters_blank() {
        assert_eq!(normalize_protect_path(Some("  /tmp/p.sock  ".to_owned())).as_deref(), Some("/tmp/p.sock"));
        assert_eq!(normalize_protect_path(Some("   ".to_owned())), None);
        assert_eq!(normalize_protect_path(None), None);
    }

    #[test]
    fn protect_fd_round_trips_an_fd_to_the_uds_server() {
        let path = std::env::temp_dir().join(format!("ripdpi-test-protect-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let server = spawn_mock_protect_server(path.clone(), 0); // 0 = parent protected the fd

        // Hand a real, valid fd (one end of a socketpair) to the server.
        let (sock, _peer) = UnixStream::pair().expect("socketpair");
        protect_fd(sock.as_raw_fd(), path.to_str().expect("utf-8 path")).expect("protect_fd must succeed");

        assert!(server.join().expect("server thread"), "server must receive a valid fd");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn protect_fd_fails_closed_on_denial_ack() {
        // The parent received the fd but VpnService.protect denied it (ack = 1):
        // protect_fd must fail closed so the caller never connects unprotected.
        let path = std::env::temp_dir().join(format!("ripdpi-test-protect-deny-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let server = spawn_mock_protect_server(path.clone(), 1); // 1 = denial

        let (sock, _peer) = UnixStream::pair().expect("socketpair");
        let err =
            protect_fd(sock.as_raw_fd(), path.to_str().expect("utf-8 path")).expect_err("denial must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);

        let _ = server.join();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn protect_fd_errors_when_no_server_listening() {
        let err = protect_fd(0, "/nonexistent/ripdpi-protect.sock").expect_err("must fail with no server");
        assert!(matches!(err.kind(), io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused));
    }

    #[test]
    fn loopback_connect_skips_protect_even_with_a_bogus_path() {
        // A bogus protect path would make protect_fd fail; a loopback target
        // must skip protect entirely, so the connect still succeeds.
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("addr");
        let stream = protected_tcp_connect_blocking(addr, Some("/definitely/not/a/socket"))
            .expect("loopback connect must skip protect and succeed");
        assert_eq!(stream.peer_addr().expect("peer").port(), addr.port());
    }

    #[test]
    fn no_path_connects_unprotected() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("addr");
        let stream = protected_tcp_connect_blocking(addr, None).expect("no-path connect must succeed");
        assert_eq!(stream.peer_addr().expect("peer").port(), addr.port());
    }
}
