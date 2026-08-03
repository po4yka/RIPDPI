use std::cell::Cell;
use std::time::{Duration, Instant};

thread_local! {
    static ACTIVE_SCAN_DEADLINE: Cell<Option<Instant>> = const { Cell::new(None) };
}

/// Run synchronous probe I/O with a scan-wide deadline available to lower layers.
///
/// The deadline is thread-local on purpose: sockets and TLS streams are synchronous,
/// and making it ambient avoids widening every diagnostics transport API. Threads
/// spawned by a runner must enter this scope themselves.
pub fn with_scan_io_deadline<T>(deadline: Option<Instant>, operation: impl FnOnce() -> T) -> T {
    ACTIVE_SCAN_DEADLINE.with(|slot| {
        let previous = slot.replace(deadline);
        let result = operation();
        slot.set(previous);
        result
    })
}

/// Return the deadline inherited by the current synchronous probe thread.
pub fn active_scan_io_deadline() -> Option<Instant> {
    ACTIVE_SCAN_DEADLINE.with(Cell::get)
}

/// Clamp a socket or resolver timeout to the remaining scan time.
///
/// `Err` means the scan deadline elapsed before an I/O operation began, so callers
/// must not start a new socket operation with a stale static timeout.
pub fn bounded_scan_io_timeout(cap: Duration) -> Result<Duration, &'static str> {
    match active_scan_io_deadline() {
        Some(deadline) => deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .map(|remaining| remaining.min(cap))
            .ok_or("scan_deadline_exceeded"),
        None => Ok(cap),
    }
}
