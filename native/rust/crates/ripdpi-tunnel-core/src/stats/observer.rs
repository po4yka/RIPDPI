use std::sync::Arc;

use super::Stats;

pub(crate) fn set_dns_latency_observer(stats: &Stats, observer: Arc<dyn Fn(u64) + Send + Sync>) {
    if let Ok(mut guard) = stats.dns_latency_observer.lock() {
        *guard = Some(observer);
    }
}

pub(crate) fn notify_dns_latency(stats: &Stats, latency_ms: u64) {
    // Issue #29 reentrancy fix: clone the Arc inside the lock, release
    // the lock, THEN invoke the observer. Holding the
    // `dns_latency_observer` Mutex across the user callback would
    // deadlock if the callback re-entered `set_dns_latency_observer`
    // (or any future `stats.*` API that also locks this mutex).
    // The Arc clone is one atomic refcount bump; the lock window is
    // therefore O(1) and bounded.
    let observer = match stats.dns_latency_observer.lock() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(observer) = observer {
        observer(latency_ms);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;

    /// Regression test for issue #29 (callback reentrancy). The
    /// observer's body invokes `set_dns_latency_observer` to
    /// replace itself; this is the canonical reentrancy pattern
    /// that would deadlock if `notify_dns_latency` held the lock
    /// across the callback. The test must complete (no deadlock)
    /// and the replacement observer must be installed.
    #[test]
    fn dns_latency_observer_reentry_does_not_deadlock() {
        let stats = Stats::default();
        let stats_outer = Arc::new(stats);
        let replacement_count = Arc::new(AtomicU64::new(0));

        // First observer: when fired, replaces itself with a second
        // observer that simply counts invocations. The replacement
        // must succeed without deadlock.
        let stats_for_cb = Arc::clone(&stats_outer);
        let counter_for_cb = Arc::clone(&replacement_count);
        let first_observer: Arc<dyn Fn(u64) + Send + Sync> = Arc::new(move |_latency| {
            // Build the replacement observer.
            let counter_for_replacement = Arc::clone(&counter_for_cb);
            let replacement: Arc<dyn Fn(u64) + Send + Sync> = Arc::new(move |_| {
                counter_for_replacement.fetch_add(1, Ordering::Relaxed);
            });
            // Re-entry: install the replacement WHILE inside the
            // first observer's invocation. Pre-fix, this would
            // deadlock on stats.dns_latency_observer Mutex.
            set_dns_latency_observer(&stats_for_cb, replacement);
        });

        set_dns_latency_observer(&stats_outer, first_observer);
        notify_dns_latency(&stats_outer, 42);
        // After the first notify, the first observer fired AND
        // replaced itself with the counting observer.
        // Second notify should hit the replacement.
        notify_dns_latency(&stats_outer, 99);
        assert_eq!(replacement_count.load(Ordering::Relaxed), 1);
    }
}
