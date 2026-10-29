//! Bounded TUN write helpers using io_uring.
//!
//! Phase 1 reads remain on the readiness-driven `try_recv` path. Submitting a
//! fixed number of blocking reads and waiting for every CQE can stall forever
//! when fewer packets than the requested batch arrive.

/// Maximum number of packets to write in a single batch.
pub const TUN_WRITE_BATCH_SIZE: usize = 64;

#[cfg(test)]
mod tests {
    use crate::bufpool::BufferHandle;
    use crate::ring::IoUringDriver;

    fn is_complete_packet_write(result: i32, packet_len: usize) -> bool {
        usize::try_from(result).is_ok_and(|written| written == packet_len)
    }

    /// Try to acquire a buffer slot large enough to hold `pkt`. Returns `None`
    /// when the pool is exhausted or `pkt` is larger than `pool.buffer_size()`,
    /// in which case the caller should use the plain `Write` fallback path.
    fn try_acquire_for_packet(uring: &IoUringDriver, pkt: &[u8]) -> Option<BufferHandle> {
        if pkt.len() > uring.buffer_size() {
            return None;
        }
        uring.acquire_buffer()
    }

    fn try_driver(capacity: u16, buffer_size: usize) -> Option<IoUringDriver> {
        IoUringDriver::start(capacity, buffer_size).ok()
    }

    #[test]
    fn try_acquire_for_packet_returns_none_when_packet_too_large() {
        let Some(driver) = try_driver(2, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let oversize = vec![0u8; 2048];
        assert!(try_acquire_for_packet(&driver, &oversize).is_none());
        // Pool still has all slots since acquire was never called.
        assert_eq!(driver.available_buffers(), 2);
    }

    #[test]
    fn try_acquire_for_packet_returns_handle_for_fitting_packet() {
        let Some(driver) = try_driver(2, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let pkt = vec![0u8; 256];
        let handle = try_acquire_for_packet(&driver, &pkt).expect("must acquire");
        assert_eq!(driver.available_buffers(), 1);
        drop(handle);
        assert_eq!(driver.available_buffers(), 2);
    }

    #[test]
    fn try_acquire_for_packet_returns_none_when_pool_exhausted() {
        let Some(driver) = try_driver(1, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let pkt = vec![0u8; 64];
        let _first = try_acquire_for_packet(&driver, &pkt).expect("first acquire");
        assert_eq!(driver.available_buffers(), 0);
        assert!(try_acquire_for_packet(&driver, &pkt).is_none());
    }

    #[test]
    fn packet_write_requires_exact_completion_length() {
        assert!(is_complete_packet_write(1500, 1500));
        assert!(!is_complete_packet_write(1499, 1500));
        assert!(!is_complete_packet_write(-libc::EIO, 1500));
    }
}
