use std::sync::OnceLock;

use io_uring::IoUring;

/// Detected io_uring capabilities for the current kernel.
#[derive(Debug, Clone, Copy, Default)]
pub struct IoUringCapabilities {
    /// `io_uring_setup(2)` succeeds (not blocked by seccomp).
    pub available: bool,
    /// `IORING_REGISTER_BUFFERS` supported.
    pub fixed_buffers: bool,
}

static IO_URING_CAPS: OnceLock<IoUringCapabilities> = OnceLock::new();

/// Return cached io_uring capabilities, probing on first call.
pub fn io_uring_capabilities() -> IoUringCapabilities {
    *IO_URING_CAPS.get_or_init(probe_io_uring)
}

fn probe_io_uring() -> IoUringCapabilities {
    let ring = match IoUring::new(8) {
        Ok(r) => r,
        Err(ref e) if e.raw_os_error() == Some(libc::ENOSYS) => {
            log::debug!("io_uring unavailable: kernel does not support io_uring_setup(2)");
            return IoUringCapabilities::default();
        }
        Err(ref e) if e.raw_os_error() == Some(libc::EPERM) => {
            log::debug!("io_uring blocked by seccomp policy (EPERM) -- common on Android");
            return IoUringCapabilities::default();
        }
        Err(e) => {
            log::debug!("io_uring unavailable: {e}");
            return IoUringCapabilities::default();
        }
    };

    let mut caps = IoUringCapabilities { available: true, ..Default::default() };

    // Probe fixed-buffer registration with a tiny test buffer.
    caps.fixed_buffers = probe_fixed_buffers(&ring);

    log::info!("io_uring probe: available={}, fixed_buffers={}", caps.available, caps.fixed_buffers);

    caps
}

fn probe_fixed_buffers(ring: &IoUring) -> bool {
    let mut buf = vec![0u8; 64];
    let iovec = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };
    // SAFETY: iovec points to a valid, live buffer.
    let result = unsafe { ring.submitter().register_buffers(&[iovec]) };
    if result.is_ok() {
        // Clean up: unregister immediately.
        let _ = ring.submitter().unregister_buffers();
    }
    result.is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_default_is_all_false() {
        let caps = IoUringCapabilities::default();
        assert!(!caps.available);
        assert!(!caps.fixed_buffers);
    }
}
