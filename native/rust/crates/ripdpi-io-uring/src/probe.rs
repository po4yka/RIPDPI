use std::sync::OnceLock;

use io_uring::IoUring;

/// Detected io_uring capabilities for the current kernel.
#[derive(Debug, Clone, Copy, Default)]
pub struct IoUringCapabilities {
    /// `io_uring_setup(2)` succeeds (not blocked by seccomp).
    pub available: bool,
    /// `IORING_OP_SEND_ZC` supported (Linux 6.0+).
    pub send_zc: bool,
    /// Zero-copy receive supported (Linux 6.20+).
    pub recv_zc: bool,
    /// `IORING_REGISTER_BUFFERS` supported.
    pub fixed_buffers: bool,
    /// `IORING_RECV_MULTISHOT` supported (Linux 6.0+).
    pub multishot_recv: bool,
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

    // Probe supported opcodes.
    let mut probe = io_uring::Probe::new();
    if ring.submitter().register_probe(&mut probe).is_ok() {
        caps.send_zc = probe.is_supported(io_uring::opcode::SendZc::CODE);
        caps.multishot_recv = probe.is_supported(io_uring::opcode::RecvMulti::CODE);
    }

    // Probe fixed-buffer registration with a tiny test buffer.
    caps.fixed_buffers = probe_fixed_buffers(&ring);

    // Zero-copy receive requires Linux 6.20+.
    caps.recv_zc = caps.available && kernel_version_at_least(6, 20);

    log::info!(
        "io_uring probe: available={}, send_zc={}, recv_zc={}, fixed_buffers={}, multishot_recv={}",
        caps.available,
        caps.send_zc,
        caps.recv_zc,
        caps.fixed_buffers,
        caps.multishot_recv,
    );

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

/// Parse kernel version from `uname` and check `major.minor >= target`.
fn kernel_version_at_least(target_major: u32, target_minor: u32) -> bool {
    let mut utsname = unsafe { std::mem::zeroed::<libc::utsname>() };
    // SAFETY: utsname is zeroed, uname fills it.
    if unsafe { libc::uname(&mut utsname) } != 0 {
        return false;
    }
    let release = unsafe { std::ffi::CStr::from_ptr(utsname.release.as_ptr()) };
    let release = release.to_string_lossy();
    parse_major_minor(&release)
        .map(|(major, minor)| major > target_major || (major == target_major && minor >= target_minor))
        .unwrap_or(false)
}

fn parse_major_minor(release: &str) -> Option<(u32, u32)> {
    let mut parts = release.split(|c: char| !c.is_ascii_digit());
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    Some((major, minor))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_major_minor_standard() {
        assert_eq!(parse_major_minor("6.20.0-rc1"), Some((6, 20)));
        assert_eq!(parse_major_minor("5.15.148-android14"), Some((5, 15)));
        assert_eq!(parse_major_minor("6.1.75"), Some((6, 1)));
    }

    #[test]
    fn parse_major_minor_edge_cases() {
        assert_eq!(parse_major_minor(""), None);
        assert_eq!(parse_major_minor("abc"), None);
        assert_eq!(parse_major_minor("6"), None);
    }

    #[test]
    fn capabilities_default_is_all_false() {
        let caps = IoUringCapabilities::default();
        assert!(!caps.available);
        assert!(!caps.send_zc);
        assert!(!caps.recv_zc);
        assert!(!caps.fixed_buffers);
        assert!(!caps.multishot_recv);
    }

    #[test]
    fn kernel_version_comparison() {
        // These are logic tests, not kernel-dependent.
        assert!(parse_major_minor("7.0.0").map(|(maj, min)| maj > 6 || (maj == 6 && min >= 20)).unwrap_or(false));
        assert!(!parse_major_minor("6.19.0").map(|(maj, min)| maj > 6 || (maj == 6 && min >= 20)).unwrap_or(false));
        assert!(parse_major_minor("6.20.0").map(|(maj, min)| maj > 6 || (maj == 6 && min >= 20)).unwrap_or(false));
    }
}
