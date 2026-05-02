#[cfg(any(target_os = "linux", target_os = "android"))]
use std::io;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::RawFd;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) fn swap_replacement_fd(target_fd: RawFd, replacement_fd: RawFd) -> io::Result<()> {
    ripdpi_privileged_ops::swap_replacement_fd(target_fd, replacement_fd)
}
