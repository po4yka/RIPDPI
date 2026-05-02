use std::os::fd::BorrowedFd;

use jni::sys::jint;
use std::os::fd::OwnedFd;

pub(crate) fn adopt_tun_fd(tun_fd: jint) -> Result<OwnedFd, String> {
    let owned_fd = unsafe { nix::unistd::dup(BorrowedFd::borrow_raw(tun_fd)) }
        .map_err(|err| format!("Failed to dup TUN fd: {err}"))?;

    nix::sys::stat::fstat(&owned_fd).map_err(|err| format!("TUN fd validation failed: {err}"))?;

    Ok(owned_fd)
}
