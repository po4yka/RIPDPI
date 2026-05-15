#[cfg(any(target_os = "linux", target_os = "android"))]
use std::io;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::RawFd;

/// Swap `replacement_fd` into the kernel slot held by `target_fd`.
///
/// # Safety
/// `replacement_fd` must be a live, owned descriptor that the caller is
/// surrendering. `target_fd` must be a live, borrowed descriptor that the
/// caller continues to own. See [`ripdpi_privileged_ops::swap_replacement_fd`].
#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) unsafe fn swap_replacement_fd(target_fd: RawFd, replacement_fd: RawFd) -> io::Result<()> {
    // SAFETY: forwarded from caller; preconditions documented above.
    unsafe { ripdpi_privileged_ops::swap_replacement_fd(target_fd, replacement_fd) }
}
