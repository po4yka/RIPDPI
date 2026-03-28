//! Platform-gated TTL helpers for probe streams.
//!
//! After a TLS handshake completes, `get_observed_ttl` reads the TTL of the
//! most recently received packet via `getsockopt(IP_TTL)`. Combined with
//! `estimate_hop_count`, this tells us roughly how many network hops the
//! server is away -- useful for locating WHERE blocking occurs in the path.

/// Read the TTL of the most recently received packet on a TCP stream.
/// Returns `None` on non-Linux platforms or if the socket option is unavailable.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) fn get_observed_ttl(stream: &std::net::TcpStream) -> Option<u8> {
    use std::os::unix::io::AsRawFd;
    let fd = stream.as_raw_fd();
    let mut ttl: libc::c_int = 0;
    let mut len: libc::socklen_t = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
    let ret = unsafe {
        libc::getsockopt(fd, libc::IPPROTO_IP, libc::IP_TTL, &mut ttl as *mut _ as *mut libc::c_void, &mut len)
    };
    if ret == 0 && ttl > 0 && ttl <= 255 {
        Some(ttl as u8)
    } else {
        None
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(crate) fn get_observed_ttl(_stream: &std::net::TcpStream) -> Option<u8> {
    None
}

/// Estimate hop count from an observed TTL value.
///
/// Assumes standard initial TTLs: 64 (Linux/macOS), 128 (Windows),
/// 255 (Solaris/network gear). The hop count is clamped to a minimum of 1
/// (same-subnet still traverses at least one hop).
pub(crate) fn estimate_hop_count(observed_ttl: u8) -> u8 {
    let reference = if observed_ttl <= 64 {
        64u8
    } else if observed_ttl <= 128 {
        128u8
    } else {
        255u8
    };
    reference.saturating_sub(observed_ttl).max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn estimate_hop_count_from_common_ttls() {
        // 64 - 64 = 0, clamped to 1 (same subnet)
        assert_eq!(estimate_hop_count(64), 1);
        // 64 - 56 = 8 hops (typical Linux/macOS server)
        assert_eq!(estimate_hop_count(56), 8);
        // 128 - 118 = 10 hops (typical Windows server)
        assert_eq!(estimate_hop_count(118), 10);
        // 255 - 247 = 8 hops (Solaris/network gear)
        assert_eq!(estimate_hop_count(247), 8);
        // Edge case: very low TTL
        assert_eq!(estimate_hop_count(1), 63);
    }

    #[test]
    fn estimate_hop_count_boundary_values() {
        // Boundary between 64 and 128 buckets
        assert_eq!(estimate_hop_count(65), 63); // 128 - 65 = 63
                                                // Boundary between 128 and 255 buckets
        assert_eq!(estimate_hop_count(129), 126); // 255 - 129 = 126
                                                  // Maximum TTL
        assert_eq!(estimate_hop_count(255), 1); // 255 - 255 = 0, clamped to 1
    }
}
