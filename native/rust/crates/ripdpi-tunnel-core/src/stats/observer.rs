use std::sync::Arc;
use std::sync::atomic::Ordering;

use super::{PacketObserver, Stats, TcpConnectObservation};

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

pub(crate) fn set_quality_observer(stats: &Stats, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
    if let Ok(mut guard) = stats.quality_observer.lock() {
        *guard = Some(observer);
    }
}

pub(crate) fn notify_quality(stats: &Stats, obs: TcpConnectObservation) {
    // Same reentrancy-safety contract as `notify_dns_latency`: clone the
    // Arc inside the lock, release the lock, THEN invoke the observer.
    let observer = match stats.quality_observer.lock() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(observer) = observer {
        observer(obs);
    }
}

pub(crate) fn set_loss_observer(stats: &Stats, observer: Arc<dyn Fn(f32) + Send + Sync>) {
    if let Ok(mut guard) = stats.loss_observer.lock() {
        *guard = Some(observer);
    }
}

pub(crate) fn notify_loss(stats: &Stats, loss_pct: f32) {
    // Same reentrancy-safety contract as `notify_dns_latency`: clone the
    // Arc inside the lock, release the lock, THEN invoke the observer.
    let observer = match stats.loss_observer.lock() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(observer) = observer {
        observer(loss_pct);
    }
}

pub(crate) fn set_packet_observer(stats: &Stats, observer: Arc<dyn PacketObserver>) {
    if let Ok(mut guard) = stats.packet_observer.lock() {
        *guard = Some(observer);
        // Publish the fast-path presence flag while STILL holding the
        // lock, so a concurrent notify that observes `true` and then
        // takes the lock is guaranteed to see the installed `Arc`
        // (the lock's release/acquire provides the happens-before edge).
        // `Relaxed` on the flag itself is sufficient -- it is only a
        // hint that gates whether the lock is taken at all.
        stats.packet_observer_present.store(true, Ordering::Relaxed);
    }
}

pub(crate) fn clear_packet_observer(stats: &Stats) {
    if let Ok(mut guard) = stats.packet_observer.lock() {
        *guard = None;
        // Ordering: the mutex publishes removal of the observer; this flag is only a fast-path hint for readers that subsequently acquire the same mutex.
        stats.packet_observer_present.store(false, Ordering::Relaxed);
    }
}

pub(crate) fn notify_inbound_packet(stats: &Stats, packet: &[u8]) {
    // Fast path: when no PCAP observer is installed (the overwhelmingly
    // common case), a single `Relaxed` atomic load lets us skip the
    // `Mutex` lock entirely on the per-packet io_loop hot path. Only when
    // an observer is present do we pay for the lock.
    if !stats.packet_observer_present.load(Ordering::Relaxed) {
        return;
    }
    // Same reentrancy-safety contract as `notify_dns_latency`: clone the
    // Arc inside the lock, release the lock, THEN invoke the observer.
    // The Arc clone is one atomic refcount bump; the lock window is
    // therefore O(1) and bounded.
    let observer = match stats.packet_observer.lock() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(observer) = observer {
        observer.on_inbound(packet);
    }
}

pub(crate) fn notify_outbound_packet(stats: &Stats, packet: &[u8]) {
    // Same fast-path gate as `notify_inbound_packet`: skip the lock when
    // no observer is installed.
    if !stats.packet_observer_present.load(Ordering::Relaxed) {
        return;
    }
    // Same reentrancy-safety contract as `notify_inbound_packet`.
    let observer = match stats.packet_observer.lock() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(observer) = observer {
        observer.on_outbound(packet);
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
