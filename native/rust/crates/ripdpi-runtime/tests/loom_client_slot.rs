//! Loom concurrency tests for the `ClientSlotGuard` acquire/release pattern.
//!
//! Run with: cargo test -p ripdpi-runtime --features loom -- loom
//!
//! `ClientSlotGuard` is `pub(super)` and therefore not accessible from outside
//! the crate. These tests inline the equivalent CAS loop logic so that loom can
//! exhaustively explore all interleavings without requiring a visibility change.

#[cfg(feature = "loom")]
mod tests {
    use loom::sync::Arc;
    use loom::sync::atomic::{AtomicUsize, Ordering};

    /// Mimics `ClientSlotGuard::acquire`: CAS-loop incrementing `active` up to `limit`.
    fn acquire(active: &Arc<AtomicUsize>, limit: usize) -> bool {
        loop {
            let current = active.load(Ordering::Relaxed);
            if current >= limit {
                return false;
            }
            if active.compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed).is_ok() {
                return true;
            }
        }
    }

    /// Mimics `ClientSlotGuard::drop`: decrement `active`.
    fn release(active: &Arc<AtomicUsize>) {
        active.fetch_sub(1, Ordering::AcqRel);
    }

    #[test]
    fn loom_acquire_respects_limit_under_contention() {
        loom::model(|| {
            let active = Arc::new(AtomicUsize::new(0));
            let limit = 1_usize;

            let a1 = active.clone();
            let t1 = loom::thread::spawn(move || {
                let got = acquire(&a1, limit);
                if got {
                    release(&a1);
                }
                got
            });

            let a2 = active.clone();
            let t2 = loom::thread::spawn(move || {
                let got = acquire(&a2, limit);
                if got {
                    release(&a2);
                }
                got
            });

            let _r1 = t1.join().unwrap();
            let _r2 = t2.join().unwrap();

            // Both threads released before joining: counter must be back at 0.
            assert_eq!(active.load(Ordering::SeqCst), 0);
        });
    }

    #[test]
    fn loom_drop_releases_slot_for_reacquire() {
        loom::model(|| {
            let active = Arc::new(AtomicUsize::new(0));
            let limit = 1_usize;

            // Thread A acquires then immediately releases.
            let a1 = active.clone();
            let t1 = loom::thread::spawn(move || {
                if acquire(&a1, limit) {
                    release(&a1);
                }
            });

            // Thread B races to acquire while A may or may not have released.
            let a2 = active.clone();
            let t2 = loom::thread::spawn(move || {
                let got = acquire(&a2, limit);
                if got {
                    release(&a2);
                }
                got
            });

            t1.join().unwrap();
            let _r2 = t2.join().unwrap();

            assert_eq!(active.load(Ordering::SeqCst), 0);
        });
    }

    #[test]
    fn loom_counter_never_exceeds_limit() {
        loom::model(|| {
            let active = Arc::new(AtomicUsize::new(0));
            let limit = 2_usize;

            let handles: Vec<_> = (0..3)
                .map(|_| {
                    let a = active.clone();
                    loom::thread::spawn(move || {
                        if acquire(&a, limit) {
                            let observed = a.load(Ordering::Acquire);
                            assert!(observed <= limit, "counter {observed} exceeded limit {limit}");
                            release(&a);
                        }
                    })
                })
                .collect();

            for h in handles {
                h.join().unwrap();
            }
            assert_eq!(active.load(Ordering::SeqCst), 0);
        });
    }
}
