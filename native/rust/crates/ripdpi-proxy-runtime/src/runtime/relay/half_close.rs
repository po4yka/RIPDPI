use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// How long one relay direction may stay idle after the other direction has finished.
///
/// A half-closed TCP connection stays usable in the open direction (RFC 9293 section 3.6),
/// so a response that follows a client FIN after a long gap must still reach the client.
/// The linger only bounds a peer that neither sends nor closes. It matches the relay
/// idle timeout (60 s), so a half-open relay holds its worker and client slot for at
/// most about two idle timeouts after the peer EOF while no data flows.
const HALF_CLOSE_LINGER: Duration = Duration::from_secs(60);

/// Tracks the idle time of one relay direction after the other direction reached EOF.
#[derive(Default)]
pub(super) struct HalfCloseLinger {
    idle_since: Option<Instant>,
}

impl HalfCloseLinger {
    /// Records traffic in this direction, which restarts the linger.
    pub(super) fn on_data(&mut self) {
        self.idle_since = None;
    }

    /// Called on a read timeout: true once the other direction has finished and this
    /// direction has been idle for the whole linger since then.
    pub(super) fn expired(&mut self, peer_done: &AtomicBool, now: Instant) -> bool {
        if !peer_done.load(Ordering::Acquire) {
            return false;
        }
        now.saturating_duration_since(*self.idle_since.get_or_insert(now)) >= HALF_CLOSE_LINGER
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn linger_starts_when_the_peer_finishes_and_restarts_on_data() {
        let peer_done = AtomicBool::new(false);
        let mut linger = HalfCloseLinger::default();
        let start = Instant::now();

        assert!(!linger.expired(&peer_done, start + HALF_CLOSE_LINGER * 2), "both directions still open");
        peer_done.store(true, Ordering::Release);
        assert!(!linger.expired(&peer_done, start), "the linger starts at the first timeout after peer EOF");
        assert!(!linger.expired(&peer_done, start + HALF_CLOSE_LINGER - Duration::from_secs(1)));
        linger.on_data();
        assert!(!linger.expired(&peer_done, start + HALF_CLOSE_LINGER), "data restarts the linger");
        assert!(linger.expired(&peer_done, start + HALF_CLOSE_LINGER * 2));
    }
}
