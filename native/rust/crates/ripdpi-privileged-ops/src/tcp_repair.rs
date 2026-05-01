use std::io;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn swap_replacement_fd(target_fd: libc::c_int, replacement_fd: libc::c_int) -> io::Result<()> {
    crate::linux::dup2_fd(replacement_fd, target_fd)?;
    crate::linux::close_fd(replacement_fd)?;
    Ok(())
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn swap_replacement_fd(_target_fd: libc::c_int, _replacement_fd: libc::c_int) -> io::Result<()> {
    crate::unsupported()
}
