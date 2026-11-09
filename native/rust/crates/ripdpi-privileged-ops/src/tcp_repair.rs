use std::io;

/// Install `replacement_fd` over `target_fd` with close-on-exec set.
/// Close a distinct replacement; an equal descriptor stays caller-owned.
///
/// # Safety
/// Both descriptor numbers must be live. The caller continues to own
/// `target_fd`. A distinct `replacement_fd` must be owned and surrendered
/// to this call; it must not be closed again by the caller.
///
/// A distinct replacement is closed on error as well as success.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub unsafe fn swap_replacement_fd(target_fd: libc::c_int, replacement_fd: libc::c_int) -> io::Result<()> {
    use std::os::fd::{AsFd, BorrowedFd, FromRawFd, OwnedFd};

    // SAFETY: caller guarantees target_fd is live and remains owned for this call.
    let target: BorrowedFd<'_> = unsafe { BorrowedFd::borrow_raw(target_fd) };
    if target_fd == replacement_fd {
        return nix::fcntl::fcntl(target, nix::fcntl::FcntlArg::F_SETFD(nix::fcntl::FdFlag::FD_CLOEXEC))
            .map(|_| ())
            .map_err(io::Error::from);
    }
    // SAFETY: caller surrenders `replacement_fd`; we now own it via OwnedFd
    // and its Drop will close on early return.
    let replacement: OwnedFd = unsafe { OwnedFd::from_raw_fd(replacement_fd) };

    crate::linux::replace_fd_cloexec(replacement.as_fd(), target)?;
    crate::linux::close_owned_fd(replacement)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub unsafe fn swap_replacement_fd(_target_fd: libc::c_int, _replacement_fd: libc::c_int) -> io::Result<()> {
    crate::unsupported()
}
