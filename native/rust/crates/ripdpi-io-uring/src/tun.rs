//! Batch TUN fd I/O helpers using io_uring.
//!
//! Provides functions that submit batched read/write operations on a TUN file
//! descriptor through the [`IoUringDriver`], reducing per-packet system call
//! overhead compared to the default `try_recv`/`try_send` path.

use std::os::fd::RawFd;

use crate::bufpool::RegisteredBufferPool;
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
/// Takes packets from the provided iterator and writes them using io_uring
/// fixed-buffer writes. Returns the number of packets successfully submitted.
///
/// This is a blocking function.
pub fn batch_tun_write(uring: &IoUringDriver, tun_fd: RawFd, packets: &[Vec<u8>]) -> std::io::Result<usize> {
    let mut written = 0;

    // For tx_queue writes, we don't use registered buffers since the packets
    // come from smoltcp's VecDeque<Vec<u8>>. We submit plain writes instead.
    // A registered-buffer variant (copy into pool, then WriteFixed) is tracked
    // in ADR-013 (P5.2.2); worth pursuing only once TUN write benchmarks exist.
    for pkt in packets.iter().take(TUN_WRITE_BATCH_SIZE) {
        let future = uring.send_zc(tun_fd, 0, pkt.len() as u32);
        let result = crate::ring::block_on_completion(future);

        if result.result >= 0 {
            written += 1;
        } else {
            log::warn!("io_uring TUN write failed: errno={}", -result.result);
            break;
        }
    }

    Ok(written)
}
