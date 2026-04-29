//! Batch TUN fd I/O helpers using io_uring.
//!
//! Provides functions that submit batched read/write operations on a TUN file
//! descriptor through the [`IoUringDriver`], reducing per-packet system call
//! overhead compared to the default `try_recv`/`try_send` path.

use std::os::fd::RawFd;

use crate::bufpool::{BufferHandle, RegisteredBufferPool};
use crate::ring::IoUringDriver;

/// Maximum number of packets to read in a single batch.
pub const TUN_READ_BATCH_SIZE: usize = 32;

/// Maximum number of packets to write in a single batch.
pub const TUN_WRITE_BATCH_SIZE: usize = 64;

/// Result of a batched TUN read operation.
pub struct TunReadBatch {
    /// (buffer_index, bytes_read) for each successfully read packet.
    pub packets: Vec<(u16, usize)>,
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
pub fn batch_tun_read(
    uring: &IoUringDriver,
    pool: &RegisteredBufferPool,
    tun_fd: RawFd,
) -> std::io::Result<TunReadBatch> {
    let mut buf_indices = Vec::with_capacity(TUN_READ_BATCH_SIZE);

    // Acquire buffers.
    for _ in 0..TUN_READ_BATCH_SIZE {
        match pool.acquire() {
            Some(handle) => {
                let idx = handle.buf_index();
                // Convert to pending so the buffer isn't returned on drop.
                handle.into_pending();
                buf_indices.push(idx);
            }
            None => break,
        }
    }

    if buf_indices.is_empty() {
        return Ok(TunReadBatch { packets: Vec::new() });
    }

    // Submit reads and collect completions.
    let mut packets = Vec::with_capacity(buf_indices.len());
    for &buf_idx in &buf_indices {
        let future = uring.recv_fixed(tun_fd, buf_idx);
        let result = crate::ring::block_on_completion(future);

        if result.result > 0 {
            packets.push((buf_idx, result.result as usize));
        } else {
            // Read failed or returned 0 bytes -- return buffer to pool.
            pool.release_by_index(buf_idx);
        }
    }

    Ok(TunReadBatch { packets })
}

/// Submit a batch of writes to the TUN fd from the smoltcp tx_queue.
///
/// Returns the number of packets successfully submitted. Per io_uring architecture note (P5.2.2)
/// each packet is staged through the registered buffer pool and submitted via
/// `IORING_OP_WRITE_FIXED` when a slot is available. When the pool is
/// exhausted, or the packet does not fit in `pool.buffer_size()`, the path
/// falls back to a caller-owned plain `opcode::Write`. The buffer slot is
/// returned to the pool only after the matching completion is reaped.
///
/// This is a blocking function.
pub fn batch_tun_write(uring: &IoUringDriver, tun_fd: RawFd, packets: &[Vec<u8>]) -> std::io::Result<usize> {
    let mut written = 0;
    let pool = uring.pool();

    for pkt in packets.iter().take(TUN_WRITE_BATCH_SIZE) {
        let result = match try_acquire_for_packet(pool, pkt) {
            Some(mut handle) => {
                let len = pkt.len();
                handle.as_mut_buf()[..len].copy_from_slice(pkt);
                handle.set_len(len);
                let buf_index = handle.buf_index();
                // Hand the slot off to the in-flight tracking; we will
                // explicitly release it below once the kernel is done.
                let pending = handle.into_pending();
                let future = uring.write_fixed(tun_fd, buf_index, len as u32);
                let result = crate::ring::block_on_completion(future);
                pending.complete(pool);
                result
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
fn try_acquire_for_packet<'a>(pool: &'a RegisteredBufferPool, pkt: &[u8]) -> Option<BufferHandle<'a>> {
    if pkt.len() > pool.buffer_size() {
        return None;
    }
    pool.acquire()
}

#[cfg(test)]
mod tests {
    use super::*;
    use io_uring::IoUring;

    /// Helper to construct a small registered-buffer pool. Returns `None` if
    /// the kernel does not support io_uring (e.g. CI runners where the ring
    /// cannot be created), so the test gracefully skips.
    fn try_pool(capacity: u16, buffer_size: usize) -> Option<(IoUring, RegisteredBufferPool)> {
        let ring = IoUring::new(8).ok()?;
        let pool = RegisteredBufferPool::new(&ring, capacity, buffer_size).ok()?;
        Some((ring, pool))
    }

    #[test]
    fn try_acquire_for_packet_returns_none_when_packet_too_large() {
        let Some((_ring, pool)) = try_pool(2, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let oversize = vec![0u8; 2048];
        assert!(try_acquire_for_packet(&pool, &oversize).is_none());
        // Pool still has all slots since acquire was never called.
        assert_eq!(pool.available(), 2);
    }

    #[test]
    fn try_acquire_for_packet_returns_handle_for_fitting_packet() {
        let Some((_ring, pool)) = try_pool(2, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let pkt = vec![0u8; 256];
        let handle = try_acquire_for_packet(&pool, &pkt).expect("must acquire");
        assert_eq!(pool.available(), 1);
        drop(handle);
        assert_eq!(pool.available(), 2);
    }

    #[test]
    fn try_acquire_for_packet_returns_none_when_pool_exhausted() {
        let Some((_ring, pool)) = try_pool(1, 1024) else {
            eprintln!("io_uring unavailable; skipping");
            return;
        };
        let pkt = vec![0u8; 64];
        let _first = try_acquire_for_packet(&pool, &pkt).expect("first acquire");
        assert_eq!(pool.available(), 0);
        assert!(try_acquire_for_packet(&pool, &pkt).is_none());
    }
}
