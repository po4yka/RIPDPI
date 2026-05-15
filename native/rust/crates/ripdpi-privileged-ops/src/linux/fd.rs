//! Linux file-descriptor helpers for socket protection and original-destination lookup.
//!
//! Syscall boundaries include descriptor duplication/close, SCM_RIGHTS fd passing,
//! and `getsockopt(SO_ORIGINAL_DST)`.

use std::io::{self, Read};
use std::mem::{size_of, zeroed};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::os::fd::{AsRawFd, BorrowedFd, OwnedFd};
use std::os::unix::net::UnixStream;
use std::time::Duration;

use crate::linux::socket_options::getsockopt_raw;

const SO_ORIGINAL_DST: libc::c_int = 80;
const IP6T_SO_ORIGINAL_DST: libc::c_int = 80;

/// `dup2(source, target)` — replace `target`'s entry in the fd table with
/// a clone of `source`. Both ends remain owned by the caller; the original
/// kernel-level entry for `target` is closed by `dup2` itself.
///
/// Typed inputs ensure the descriptors outlive this call (the borrow
/// checker enforces it); `pub(crate)` because the only legitimate callers
/// live inside this crate.
pub(crate) fn dup2_fd(source: BorrowedFd<'_>, target: BorrowedFd<'_>) -> io::Result<()> {
    // SAFETY: `source` and `target` are live for the duration of this call
    // (enforced by `BorrowedFd<'_>` lifetimes). `dup2` accepts any pair of
    // valid descriptor numbers.
    let rc = unsafe { libc::dup2(source.as_raw_fd(), target.as_raw_fd()) };
    if rc >= 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Consume an owned descriptor, closing it. Equivalent to `drop(fd)` but
/// surfaces the close error, which the public `swap_replacement_fd`
/// contract historically reported.
pub(crate) fn close_owned_fd(fd: OwnedFd) -> io::Result<()> {
    let raw = fd.as_raw_fd();
    std::mem::forget(fd);
    // SAFETY: `fd` was an `OwnedFd`, i.e. it owned `raw` exclusively and we
    // have forgotten it without dropping, so no other code path will close
    // `raw`. This call closes it exactly once.
    let rc = unsafe { libc::close(raw) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

pub fn protect_socket<T: AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{sendmsg, ControlMessage, MsgFlags};
    use std::io::IoSlice;

    tracing::debug!(path = path, "protect_socket: connecting");
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
    if ack[0] != 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect() rejected socket"));
    }
    tracing::debug!(path = path, "protect_socket: fd protected");
    Ok(())
}

pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    let fd = stream.as_raw_fd();

    // SAFETY: `fd` is a live TCP socket; the kernel writes a `sockaddr_storage`
    // for SO_ORIGINAL_DST / IP6T_SO_ORIGINAL_DST.
    if let Ok((storage, _)) = unsafe { getsockopt_raw::<libc::sockaddr_storage>(fd, libc::IPPROTO_IP, SO_ORIGINAL_DST) }
    {
        return storage_to_socket_addr(&storage);
    }
    let (storage, _) =
        unsafe { getsockopt_raw::<libc::sockaddr_storage>(fd, libc::IPPROTO_IPV6, IP6T_SO_ORIGINAL_DST) }?;
    storage_to_socket_addr(&storage)
}

pub(crate) fn peer_addr(fd: libc::c_int) -> io::Result<libc::sockaddr_storage> {
    // SAFETY: `storage` is zero-initialized and `getpeername` writes at most
    // `len` bytes into it for the valid socket descriptor `fd`.
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    let mut len = size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    let rc = unsafe { libc::getpeername(fd, (&mut storage as *mut libc::sockaddr_storage).cast(), &mut len) };
    if rc == 0 {
        Ok(storage)
    } else {
        Err(io::Error::last_os_error())
    }
}

pub(crate) fn storage_to_socket_addr(storage: &libc::sockaddr_storage) -> io::Result<SocketAddr> {
    match i32::from(storage.ss_family) {
        libc::AF_INET => {
            // SAFETY: family tag was checked to be AF_INET.
            let sin = unsafe { &*(storage as *const libc::sockaddr_storage).cast::<libc::sockaddr_in>() };
            let ip = Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr));
            let port = u16::from_be(sin.sin_port);
            Ok(SocketAddr::new(IpAddr::V4(ip), port))
        }
        libc::AF_INET6 => {
            // SAFETY: family tag was checked to be AF_INET6.
            let sin6 = unsafe { &*(storage as *const libc::sockaddr_storage).cast::<libc::sockaddr_in6>() };
            let ip = Ipv6Addr::from(sin6.sin6_addr.s6_addr);
            let port = u16::from_be(sin6.sin6_port);
            Ok(SocketAddr::new(IpAddr::V6(ip), port))
        }
        _ => {
            Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socket family in original destination lookup"))
        }
    }
}
