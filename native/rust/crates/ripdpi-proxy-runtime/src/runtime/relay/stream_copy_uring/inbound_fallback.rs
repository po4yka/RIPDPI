use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use crate::sync::Arc;

use super::super::session::RelaySharedSession;
use super::cleanup::shutdown_direction;
use super::freeze_detector::FreezeDetector;

/// Fallback: normal inbound copy when ZC buffers are exhausted.
pub(super) fn copy_inbound_fallback(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: RelaySharedSession,
    peer_done: Arc<AtomicBool>,
    mut detector: FreezeDetector,
    freeze_detected: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                session.observe_inbound_payload(&buffer[..n]);
                writer.write_all(&buffer[..n])?;
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
