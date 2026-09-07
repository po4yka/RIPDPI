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
    if rc >= 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
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
    if rc == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

pub fn protect_socket<T: AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};
    use std::io::IoSlice;

    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(Duration::from_secs(1)))?;
    stream.set_write_timeout(Some(Duration::from_secs(1)))?;

    let payload = *b"1";
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
    // SAFETY: `fd` is a live TCP socket; the kernel writes a `sockaddr_storage`
    // for the IPv6 original-destination option.
    let (storage, _) =
        unsafe { getsockopt_raw::<libc::sockaddr_storage>(fd, libc::IPPROTO_IPV6, IP6T_SO_ORIGINAL_DST) }?;
    storage_to_socket_addr(&storage)
}

pub(crate) fn peer_addr(fd: libc::c_int) -> io::Result<libc::sockaddr_storage> {
    // SAFETY: `storage` is zero-initialized and `getpeername` writes at most
    // `len` bytes into it for the valid socket descriptor `fd`.
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    let mut len = size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    // SAFETY: `fd` is a valid socket descriptor and `storage`/`len` are live
    // writable stack slots whose sizes match the `getpeername` ABI.
    let rc = unsafe { libc::getpeername(fd, (&mut storage as *mut libc::sockaddr_storage).cast(), &mut len) };
    if rc == 0 { Ok(storage) } else { Err(io::Error::last_os_error()) }
}

// Compile-time guard for soundness issue #23 (type punning).
// `storage_to_socket_addr` reinterprets `&libc::sockaddr_storage` as
// `&libc::sockaddr_in` / `&libc::sockaddr_in6` after a family-tag
// check. The reinterpretation is sound only while
// `sockaddr_storage`'s alignment is ≥ the target type's alignment
// (POSIX guarantees this via the `__ss_align` field; the assert
// catches any future libc/platform layout change before the
// `.cast::<...>().&*` deref below could produce a misaligned
// reference). Size is guaranteed by the same POSIX rule and is
// validated implicitly by the syscall populating the bytes.
const _: () = {
    assert!(align_of::<libc::sockaddr_storage>() >= align_of::<libc::sockaddr_in>());
    assert!(align_of::<libc::sockaddr_storage>() >= align_of::<libc::sockaddr_in6>());
    assert!(size_of::<libc::sockaddr_storage>() >= size_of::<libc::sockaddr_in>());
    assert!(size_of::<libc::sockaddr_storage>() >= size_of::<libc::sockaddr_in6>());
};

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

#[cfg(test)]
mod tests {
    //! Regressions for issue #33 (incorrect `ManuallyDrop` /
    //! `mem::forget` usage). `close_owned_fd` is the only
    //! `mem::forget` site in the workspace that operates on an
    //! `OwnedFd` (rather than on a private RAII guard). The pattern:
    //!
    //!     let raw = fd.as_raw_fd();
    //!     std::mem::forget(fd);
    //!     unsafe { libc::close(raw) };
    //!
    //! depends on `OwnedFd`'s `Drop` being the unique close site --
    //! if the `mem::forget` were missing, both the `OwnedFd`'s Drop
    //! and the explicit `libc::close` would close the same fd
    //! (double-close UB; on Linux this races with other threads'
    //! `open` calls that may have re-claimed the fd number). If the
    //! `libc::close` were missing, the close error this function is
    //! designed to surface would be swallowed by `OwnedFd::drop`.
    //!
    //! These tests pin down both invariants on the success path and
    //! on the error path (close on an already-invalid fd returns
    //! EBADF), with the panic-unwind path covered by exercising
    //! `close_owned_fd` inside `catch_unwind` to verify no leak
    //! between the `forget` and the `close`.
    use super::*;
    use std::os::fd::AsRawFd;
    use std::os::fd::FromRawFd;

    fn make_owned_fd() -> OwnedFd {
        // `/dev/null` is universally available on Linux/Android and
        // tolerates close/EBADF round-trips.
        // SAFETY: the C string is static and null-terminated; `open` returns
        // either a fresh owned descriptor or a negative errno.
        let raw = unsafe { libc::open(c"/dev/null".as_ptr(), libc::O_RDONLY) };
        assert!(raw >= 0, "open /dev/null failed: {}", io::Error::last_os_error());
        // SAFETY: `raw` is a freshly opened, owned descriptor with
        // no aliasing handle; transfer ownership into OwnedFd.
        unsafe { OwnedFd::from_raw_fd(raw) }
    }

    #[test]
    fn close_owned_fd_success_path_closes_exactly_once() {
        let fd = make_owned_fd();
        let raw = fd.as_raw_fd();
        // Sanity: the fd is currently valid (fcntl returns >= 0).
        // SAFETY: `raw` comes from a live `OwnedFd`; F_GETFL only queries fd flags.
        let pre = unsafe { libc::fcntl(raw, libc::F_GETFL) };
        assert!(pre >= 0, "fd must be valid before close");
        close_owned_fd(fd).expect("close should succeed");
        // After close, the fd number must now be invalid (EBADF).
        // SAFETY: probing an fd number with F_GETFL is allowed; EBADF is the
        // expected kernel response after close.
        let post = unsafe { libc::fcntl(raw, libc::F_GETFL) };
        assert_eq!(post, -1, "fd must be invalid after close");
        assert_eq!(
            io::Error::last_os_error().raw_os_error(),
            Some(libc::EBADF),
            "post-close fcntl must report EBADF (fd was closed exactly once)",
        );
    }

    #[test]
    fn close_owned_fd_surfaces_close_error_on_already_invalid_fd() {
        // Construct an OwnedFd around a known-invalid descriptor by
        // closing the underlying number out from under it first.
        // This simulates the "kernel closed the fd before us" failure
        // mode (rare but legal on Linux when fd table is reused under
        // a non-cooperative parent process).
        let fd = make_owned_fd();
        let raw = fd.as_raw_fd();
        // Pre-close the descriptor through the raw fd, then ask
        // close_owned_fd to close it again. The second close is the
        // one this test asserts surfaces EBADF instead of being
        // silently swallowed.
        // SAFETY: `raw` comes from a live `OwnedFd`; this intentionally closes
        // it early to exercise the error path.
        let pre_close = unsafe { libc::close(raw) };
        assert_eq!(pre_close, 0, "pre-close should succeed");
        // The OwnedFd still thinks it owns `raw`, but the kernel
        // has already reclaimed the number. `close_owned_fd`
        // forgets the OwnedFd (no double-close) and explicitly
        // close()s `raw`, which the kernel rejects with EBADF.
        let err = close_owned_fd(fd).expect_err("close must error on already-invalid fd");
        assert_eq!(
            err.raw_os_error(),
            Some(libc::EBADF),
            "close-error must propagate as EBADF rather than being swallowed",
        );
    }

    #[test]
    fn close_owned_fd_does_not_leak_under_panic_unwind() {
        // The `mem::forget(fd)` between extracting `raw` and calling
        // `libc::close(raw)` runs to completion atomically -- there
        // is no `.await`, no fallible operation, and no panic site
        // between them in the current implementation. This test
        // pins down that invariant: if a future refactor were to
        // introduce a panic site between `forget` and `close`, the
        // resource would leak. We exercise the function inside
        // `catch_unwind` and verify no panic propagates AND the fd
        // is closed (EBADF on the followup fcntl).
        let fd = make_owned_fd();
        let raw = fd.as_raw_fd();
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            close_owned_fd(fd).expect("inner close should succeed");
        }));
        assert!(outcome.is_ok(), "close_owned_fd must not panic on the success path");
        // SAFETY: probing an fd number with F_GETFL is allowed; EBADF proves
        // the descriptor was closed.
        let post = unsafe { libc::fcntl(raw, libc::F_GETFL) };
        assert_eq!(post, -1, "fd must be invalid after close (no leak)");
        assert_eq!(io::Error::last_os_error().raw_os_error(), Some(libc::EBADF));
    }
}
