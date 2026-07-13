//! Batch TUN fd I/O helpers using io_uring.
//!
//! Provides functions that submit batched read/write operations on a TUN file
//! descriptor through the [`IoUringDriver`], reducing per-packet system call
//! overhead compared to the default `try_recv`/`try_send` path.

use std::os::fd::BorrowedFd;

use crate::bufpool::BufferHandle;
use crate::ring::IoUringDriver;

/// Maximum number of packets to read in a single batch.
pub const TUN_READ_BATCH_SIZE: usize = 32;

/// Maximum number of packets to write in a single batch.
pub const TUN_WRITE_BATCH_SIZE: usize = 64;

/// Result of a batched TUN read operation.
pub struct TunReadBatch {
    /// Owning registered-buffer leases for each successfully read packet.
    pub packets: Vec<BufferHandle>,
}

/// Submit a batch of reads on the TUN fd and collect results.
///
/// Acquires up to `TUN_READ_BATCH_SIZE` buffers from the pool, submits them
/// as fixed reads to the io_uring driver, and returns the completed reads.
///
/// Buffers for failed or zero-length reads are returned to the pool. The
/// caller is responsible for returning successful buffers after consuming
/// their contents.
///
/// This is a blocking function intended to be called from an async context
/// via `tokio::task::spawn_blocking` or similar.
pub fn batch_tun_read(uring: &IoUringDriver, tun_fd: BorrowedFd<'_>) -> std::io::Result<TunReadBatch> {
    let mut futures = Vec::with_capacity(TUN_READ_BATCH_SIZE);

    for _ in 0..TUN_READ_BATCH_SIZE {
        match uring.acquire_buffer() {
            Some(handle) => futures.push(uring.recv_fixed(tun_fd, handle)),
            None => break,
        }
    }

    if futures.is_empty() {
        return Ok(TunReadBatch { packets: Vec::new() });
    }

    let mut packets = Vec::with_capacity(futures.len());
    for future in futures {
        let result = crate::ring::block_on_completion(future);
        if result.result > 0 {
            packets.push(result.into_buffer().expect("fixed read completion must return its buffer lease"));
        }
    }

    Ok(TunReadBatch { packets })
}

/// Submit a batch of writes to the TUN fd from the smoltcp tx_queue.
///
/// Returns the number of packets successfully submitted. Each packet is staged
/// through the registered buffer pool and submitted via `IORING_OP_WRITE_FIXED`
/// when a slot is available. When the pool is exhausted, or the packet does not
/// fit in `pool.buffer_size()`, the path falls back to a caller-owned plain
/// `opcode::Write`. The buffer slot is returned to the pool only after the
/// matching completion is reaped.
///
/// This is a blocking function.
pub fn batch_tun_write(uring: &IoUringDriver, tun_fd: BorrowedFd<'_>, packets: &[Vec<u8>]) -> std::io::Result<usize> {
    let mut written = 0;
    for pkt in packets.iter().take(TUN_WRITE_BATCH_SIZE) {
        let result = match try_acquire_for_packet(uring, pkt) {
            Some(mut handle) => {
                let len = pkt.len();
                handle.as_mut_buf()[..len].copy_from_slice(pkt);
                handle.set_len(len);
                crate::ring::block_on_completion(uring.write_fixed(tun_fd, handle))
            }
            None => {
                // Pool exhausted or packet larger than buffer_size; fall
                // back to a plain caller-owned write.
                let future = uring.write(tun_fd, pkt.clone());
                crate::ring::block_on_completion(future)
            }
        };

        if result.result >= 0 {
            written += 1;
        } else {
            log::warn!("io_uring TUN write failed: errno={}", -result.result);
            break;
        }
    }

    Ok(written)
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

#[cfg(test)]
mod tests {
    use super::*;
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
}
