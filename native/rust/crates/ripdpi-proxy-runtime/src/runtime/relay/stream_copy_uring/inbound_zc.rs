use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::os::fd::AsFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_io_uring::IoUringDriver;

use super::super::session::RelaySharedSession;
use super::cleanup::shutdown_direction;
use super::freeze_detector::FreezeDetector;
use super::inbound_fallback::copy_inbound_fallback;
use super::uring_buffers::{acquire_registered_buffer, block_on_completion};
use crate::runtime::types::RuntimeRelayTimeouts;

/// Inbound copy using an io_uring fixed-buffer write.
///
/// Reads from the upstream socket into a registered buffer, then submits
/// `IORING_OP_WRITE_FIXED` to write to the client socket directly from the
/// registered buffer (no copy through userspace). Falls back to a normal
/// write if the registered-buffer pool is exhausted.
///
/// # Why not `IORING_OP_SEND_ZC`
///
/// `SEND_ZC` is a *two-phase* op: the kernel keeps reading the submitted
/// buffer until it emits a *second* CQE flagged `IORING_CQE_F_NOTIF`,
/// distinct from the first (result) CQE. The completion registry resolves on
/// the first CQE for a token and cannot distinguish the NOTIF CQE, so a
/// SEND_ZC-based loop returns the buffer to the pool on the first CQE while
/// the kernel is still DMA-reading it — a use-after-free / data race the next
/// iteration triggers by overwriting the slot. Correct NOTIF-CQE handling on
/// this synchronous, kernel-DMA path is unverified on the supported targets,
/// so this path uses single-completion `WRITE_FIXED` instead: the kernel is
/// done with the buffer the instant its one CQE arrives, making the
/// return-to-pool unambiguously safe.
pub(super) fn copy_inbound_zc(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: RelaySharedSession,
    peer_done: Arc<AtomicBool>,
    timeouts: RuntimeRelayTimeouts,
    freeze_detected: Arc<AtomicBool>,
    uring: &IoUringDriver,
) -> io::Result<()> {
    let mut detector =
        FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);

    loop {
        let Some(mut handle) = acquire_registered_buffer(uring) else {
            return copy_inbound_fallback(reader, writer, session, peer_done, detector, freeze_detected);
        };

        match reader.read(handle.as_mut_buf()) {
            Ok(0) => break,
            Ok(n) => {
                if !handle.set_len(n) {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "read exceeded registered buffer capacity"));
                }
                session.observe_inbound_payload(&handle[..]);

                // Single-completion fixed-buffer write. The driver owns the
                // handle across the blocking wait, then returns it in the CQE
                // result so the registered buffer remains valid throughout.
                let result = block_on_completion(uring.write_fixed(writer.as_fd(), handle));
                let result_code = result.result;
                let handle = result.into_buffer().expect("fixed write completion must return its buffer lease");

                if result_code < 0 {
                    // The fixed-buffer write failed. The payload still lives in
                    // the registered buffer (`handle` is alive), so flush the
                    // remainder through the plain socket write rather than
                    // silently dropping it, then surface any error to the
                    // caller. Never proceed past a failed write.
                    writer.write_all(&handle[..])?;
                } else {
                    // Positive result is the byte count the kernel accepted. A
                    // short write can happen on a partially-writable socket;
                    // flush whatever the kernel did not take from the same
                    // (still-valid) registered buffer.
                    let written = result_code as usize;
                    if written < n {
                        writer.write_all(&handle[written..])?;
                    }
                }

                detector.record_bytes(n);
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => return Err(err),
        }
        // `handle` drops here, returning the registered buffer to the pool.
        // This is reached only after the single CQE has been reaped above, so
        // the kernel is guaranteed to be done reading the slot.
    }

    peer_done.store(true, Ordering::Release);
    shutdown_direction(&writer, &reader);
    Ok(())
}
