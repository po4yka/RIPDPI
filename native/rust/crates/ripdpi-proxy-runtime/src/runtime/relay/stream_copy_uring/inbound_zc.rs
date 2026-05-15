use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_io_uring::IoUringDriver;

use super::super::session::RelaySharedSession;
use super::cleanup::shutdown_direction;
use super::freeze_detector::FreezeDetector;
use super::inbound_fallback::copy_inbound_fallback;
use super::uring_buffers::{acquire_registered_buffer, block_on_completion, pool_buf_slice};
use crate::runtime::types::RuntimeRelayTimeouts;

/// Inbound copy using io_uring zero-copy send.
///
/// Reads from the upstream socket into a registered buffer, then submits
/// `IORING_OP_SEND_ZC` to write to the client socket without copying
/// through userspace. Falls back to normal write if ZC send fails.
pub(super) fn copy_inbound_zc(
    mut reader: TcpStream,
    mut writer: TcpStream,
    writer_fd: i32,
    session: RelaySharedSession,
    peer_done: Arc<AtomicBool>,
    timeouts: RuntimeRelayTimeouts,
    freeze_detected: Arc<AtomicBool>,
    uring: &IoUringDriver,
) -> io::Result<()> {
    let pool = uring.pool();
    let mut detector =
        FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);

    loop {
        let mut handle = match acquire_registered_buffer(pool) {
            Some(handle) => handle,
            None => return copy_inbound_fallback(reader, writer, session, peer_done, detector, freeze_detected),
        };

        match reader.read(handle.as_mut_buf()) {
            Ok(0) => break,
            Ok(n) => {
                handle.set_len(n);
                session.observe_inbound_payload(&handle[..]);

                let future = uring.send_zc(writer_fd, handle.buf_index(), n as u32);
                let pending = handle.into_pending();
                let result = block_on_completion(future);

                if result.result < 0 {
                    let buf_index = pending.buf_index();
                    pending.complete();
                    writer.write_all(&pool_buf_slice(pool, buf_index, n))?;
                } else {
                    // Wait for notification CQE before returning buffer. For this
                    // initial implementation, the buffer is returned immediately.
                    pending.complete();
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
    }

    peer_done.store(true, Ordering::Release);
    shutdown_direction(&writer, &reader);
    Ok(())
}
