use std::os::fd::{BorrowedFd, FromRawFd, OwnedFd};

use jni::sys::jint;
use nix::fcntl::{fcntl, FcntlArg};

/// Duplicate the TUN file descriptor received from Kotlin via a `.fd()` peek,
/// returning an `OwnedFd` that the Rust side owns exclusively.
///
/// # Contract
///
/// The caller (Kotlin, `VpnTunnelSessionProvider.tunFd`) passes a `.fd()` peek
/// — NOT `detachFd()` — so Kotlin retains sole ownership of the original
/// `ParcelFileDescriptor` and closes it via `VpnTunnelSession.close()`.  Rust
/// must never close the original `tun_fd` integer; it only ever closes the dup
/// returned here.
///
/// # Why `F_DUPFD_CLOEXEC` instead of plain `dup()`
///
/// Plain `dup(2)` does not set `O_CLOEXEC` on the new descriptor.  Between
/// the `dup()` call and `AsyncDevice::from_fd` registering the fd with the
/// tokio reactor, `root_helper::register_for_worker` may `exec()` a child
/// process.  Without `O_CLOEXEC` that child inherits the TUN fd and keeps it
/// open after `run_tunnel` returns, preventing the kernel from releasing the
/// TUN device.  `F_DUPFD_CLOEXEC` is atomic — it duplicates and sets the flag
/// in a single syscall, eliminating the TOCTOU window.
pub(crate) fn adopt_tun_fd(tun_fd: jint) -> Result<OwnedFd, String> {
    // SAFETY: `tun_fd` is a valid open file descriptor whose kernel lifetime
    // is guaranteed by Kotlin's `ParcelFileDescriptor` for the duration of
    // this call.  `BorrowedFd::borrow_raw` does not take ownership; we borrow
    // only long enough to pass it to `fcntl`.  `fcntl(F_DUPFD_CLOEXEC(0))`
    // returns a new, independent kernel file descriptor >= 0 that this process
    // now owns exclusively.  Converting that raw integer to `OwnedFd` via
    // `from_raw_fd` transfers ownership into Rust's RAII system: the returned
    // `OwnedFd` is the sole owner and will call `libc::close` on drop.
    let raw_dup = unsafe { fcntl(BorrowedFd::borrow_raw(tun_fd), FcntlArg::F_DUPFD_CLOEXEC(0)) }
        .map_err(|err| format!("Failed to dup TUN fd with CLOEXEC: {err}"))?;

    // SAFETY: `fcntl(F_DUPFD_CLOEXEC)` returned a non-negative file descriptor
    // that is not held by any other Rust value — we are the first to wrap it.
    // `OwnedFd::from_raw_fd` takes exclusive ownership and closes it on drop.
    let owned_fd = unsafe { OwnedFd::from_raw_fd(raw_dup) };

    nix::sys::stat::fstat(&owned_fd).map_err(|err| format!("TUN fd validation failed: {err}"))?;

    Ok(owned_fd)
}
