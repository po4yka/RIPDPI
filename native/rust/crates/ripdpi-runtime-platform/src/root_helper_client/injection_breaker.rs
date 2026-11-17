//! Pauses raw-packet injection after a root-helper timeout or transport error.
//!
//! Injection runs synchronously on the tunnel io loop. Once the helper has failed to answer, each later packet would wait for the full timeout again, so injection is refused at once for a cooldown and one call then retries.

use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

/// How long injection is refused after a helper timeout or transport error.
pub(super) const INJECTION_BREAKER_COOLDOWN: Duration = Duration::from_secs(30);

pub(super) struct InjectionBreaker {
    epoch: Instant,
    cooldown_ms: u64,
    /// Milliseconds after `epoch` until which injection is refused; 0 when closed.
    open_until_ms: AtomicU64,
}

impl InjectionBreaker {
    pub(super) fn new(cooldown: Duration) -> Self {
        Self {
            epoch: Instant::now(),
            cooldown_ms: u64::try_from(cooldown.as_millis()).unwrap_or(u64::MAX).max(1),
            open_until_ms: AtomicU64::new(0),
        }
    }

    /// Runs `inject` unless the breaker is open, and opens the breaker when `inject` fails with a timeout or a transport error.
    ///
    /// Every atomic access is `Relaxed`: the deadline is the only state and no other memory is published through it, so no happens-before edge is needed; the modification order of this one location orders the load, the retry `compare_exchange` and the swaps.
    pub(super) fn call(&self, inject: impl FnOnce() -> io::Result<()>) -> io::Result<()> {
        let open_until = self.open_until_ms.load(Ordering::Relaxed);
        if open_until != 0 {
            let now = self.now_ms();
            if now < open_until {
                return Err(paused());
            }
            // Only one caller wins the retry after the cooldown; the others still see an open breaker.
            if self
                .open_until_ms
                .compare_exchange(open_until, self.deadline(now), Ordering::Relaxed, Ordering::Relaxed)
                .is_err()
            {
                return Err(paused());
            }
        }

        let result = inject();
        match &result {
            Err(error) if is_transport_failure(error) => {
                if self.open_until_ms.swap(self.deadline(self.now_ms()), Ordering::Relaxed) == 0 {
                    tracing::warn!(
                        cooldown_ms = self.cooldown_ms,
                        "root helper packet injection paused after a helper failure: {error}"
                    );
                }
            }
            _ => {
                if open_until != 0 && self.open_until_ms.swap(0, Ordering::Relaxed) != 0 {
                    tracing::info!("root helper packet injection resumed");
                }
            }
        }
        result
    }

    fn now_ms(&self) -> u64 {
        u64::try_from(self.epoch.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    fn deadline(&self, now_ms: u64) -> u64 {
        now_ms.saturating_add(self.cooldown_ms)
    }
}

/// A timeout or a broken connection: the helper did not answer.
fn is_transport_failure(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::TimedOut
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::NotFound
            | io::ErrorKind::ConnectionRefused
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::NotConnected
            | io::ErrorKind::BrokenPipe
            | io::ErrorKind::UnexpectedEof
    )
}

#[derive(Debug)]
struct InjectionPaused;

impl std::fmt::Display for InjectionPaused {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("root helper packet injection is paused after a helper failure")
    }
}

impl std::error::Error for InjectionPaused {}

fn paused() -> io::Error {
    io::Error::new(io::ErrorKind::WouldBlock, InjectionPaused)
}

/// Reports whether `error` is a refusal from an open injection breaker.
pub(crate) fn is_injection_paused(error: &io::Error) -> bool {
    matches!(error.get_ref(), Some(inner) if inner.is::<InjectionPaused>())
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::io;
    use std::time::Duration;

    use super::{InjectionBreaker, is_injection_paused};

    fn timed_out() -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::TimedOut, "helper did not answer"))
    }

    #[test]
    fn transport_failure_pauses_injection_until_the_cooldown_ends() {
        let breaker = InjectionBreaker::new(Duration::from_millis(100));
        let calls = Cell::new(0);
        let attempt = |result: fn() -> io::Result<()>| {
            breaker.call(|| {
                calls.set(calls.get() + 1);
                result()
            })
        };

        assert_eq!(attempt(timed_out).expect_err("timeout").kind(), io::ErrorKind::TimedOut);
        let refused = attempt(|| Ok(())).expect_err("open breaker refuses");
        assert!(is_injection_paused(&refused));
        assert_eq!(calls.get(), 1, "an open breaker must not run the injection");

        std::thread::sleep(Duration::from_millis(150));
        assert!(attempt(timed_out).is_err());
        assert_eq!(calls.get(), 2, "one call retries after the cooldown");
        assert!(is_injection_paused(&attempt(|| Ok(())).expect_err("a failed retry reopens")));
        assert_eq!(calls.get(), 2);

        std::thread::sleep(Duration::from_millis(150));
        attempt(|| Ok(())).expect("retry succeeds");
        attempt(|| Ok(())).expect("a successful retry closes the breaker");
        assert_eq!(calls.get(), 4);
    }

    #[test]
    fn helper_error_reply_keeps_injection_enabled() {
        let breaker = InjectionBreaker::new(Duration::from_secs(30));
        let calls = Cell::new(0);
        for _ in 0..2 {
            let error = breaker
                .call(|| {
                    calls.set(calls.get() + 1);
                    Err(io::Error::other("helper rejected the packet"))
                })
                .expect_err("helper error");
            assert!(!is_injection_paused(&error));
        }
        assert_eq!(calls.get(), 2);
    }
}
