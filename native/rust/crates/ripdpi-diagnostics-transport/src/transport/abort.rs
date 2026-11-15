use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

pub(super) type AbortCheck<'a> = &'a dyn Fn() -> Option<&'static str>;
pub(super) const ABORT_POLL_INTERVAL: Duration = Duration::from_millis(25);

pub(super) fn receive_until<T>(
    receiver: &Receiver<T>,
    deadline: Option<Instant>,
    should_abort: Option<AbortCheck<'_>>,
) -> Result<Result<T, RecvTimeoutError>, &'static str> {
    loop {
        if let Some(reason) = should_abort.and_then(|check| check()) {
            return Err(reason);
        }
        let now = Instant::now();
        let remaining = deadline.map(|deadline| deadline.saturating_duration_since(now));
        let wait = match (remaining, should_abort) {
            (Some(remaining), Some(_)) => remaining.min(ABORT_POLL_INTERVAL),
            (None, Some(_)) => ABORT_POLL_INTERVAL,
            (Some(remaining), None) => remaining,
            (None, None) => return Ok(receiver.recv().map_err(|_| RecvTimeoutError::Disconnected)),
        };
        match receiver.recv_timeout(wait) {
            Err(RecvTimeoutError::Timeout) if should_abort.is_some() && remaining.is_none_or(|value| value > wait) => {}
            result => return Ok(result),
        }
    }
}
