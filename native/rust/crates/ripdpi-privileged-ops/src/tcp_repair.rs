use std::io;

/// Transfer ownership of `replacement_fd` into the slot held by
/// `target_fd`, then close the original `replacement_fd`.
///
/// # Safety
/// `replacement_fd` must be a live, owned descriptor that the caller is
/// surrendering to this call. `target_fd` must be a live, borrowed
/// descriptor that the caller continues to own (its kernel-level entry is
/// replaced by `dup2` but the integer remains valid afterwards).
///
/// This function consumes `replacement_fd` — it must not be closed again
/// by the caller. On error the descriptor is still closed (the previous
/// version of this helper leaked it on `dup2` failure).
#[cfg(any(target_os = "linux", target_os = "android"))]
pub unsafe fn swap_replacement_fd(target_fd: libc::c_int, replacement_fd: libc::c_int) -> io::Result<()> {
    use std::os::fd::{AsFd, BorrowedFd, FromRawFd, OwnedFd};

    // SAFETY: caller surrenders `replacement_fd`; we now own it via OwnedFd
    // and its Drop will close on early return.
    let replacement: OwnedFd = unsafe { OwnedFd::from_raw_fd(replacement_fd) };
    // SAFETY: caller guarantees `target_fd` is live for the duration.
    let target: BorrowedFd<'_> = unsafe { BorrowedFd::borrow_raw(target_fd) };

    crate::linux::dup2_fd(replacement.as_fd(), target)?;
    crate::linux::close_owned_fd(replacement)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub unsafe fn swap_replacement_fd(_target_fd: libc::c_int, _replacement_fd: libc::c_int) -> io::Result<()> {
    crate::unsupported()
}
