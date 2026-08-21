//! Issue #33 canary: reference implementation of the workspace's
//! ManuallyDrop discipline.
//!
//! The production workspace forbids `ManuallyDrop<T>` in
//! `native/rust/crates/*/src/**` (see
//! `docs/rust-soundness-policy.md` § "ManuallyDrop<T> forbiddance").
//! This integration test lives under `tests/` so the scanner
//! (`scripts/ci/check_unsafe_boundaries.py`) does not flag it, yet
//! its existence pins down the canonical correct discipline so any
//! future allowlisted use has a worked example to mirror.
//!
//! The discipline modelled here is the soundness-boundary
//! contract documented in the policy:
//!
//!   1. The `ManuallyDrop` field is PRIVATE -- behind a `mod
//!      private`. No external code can construct, move out of, or
//!      read through the wrapper except via the module's curated
//!      API.
//!   2. Drop state is EXPLICIT -- a `DropState` enum
//!      (`NotInitialized | Initialized | ManuallyDropped`)
//!      transitions only at well-defined sites. The state
//!      transition, not the `// SAFETY:` comment, is what proves
//!      the manual drop runs exactly once.
//!   3. Panic safety is COMMITTED -- the type's Drop impl checks
//!      the state and runs the manual drop only from the
//!      `Initialized` state, so a panic between construction and
//!      `dispose` still hits the manual drop exactly once via
//!      unwind.
//!
//! Run under Miri to verify no double-drop, no use-after-free, no
//! leak across the panic-unwind path:
//!
//!     cargo +nightly miri test -p soundness-canaries \
//!         --test manuallydrop_canary

mod canary {
    //! Private module -- the `ManuallyDrop`-bearing struct does
    //! NOT escape this module. External code interacts only with
    //! the public `dispose` / `peek` API.
    use std::mem::ManuallyDrop;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Global drop counter for `Bomb`. Tests assert against this to
    /// verify single-drop / no-double-drop / no-leak.
    pub static BOMB_DROPS: AtomicUsize = AtomicUsize::new(0);

    /// `Bomb` is the synthetic payload that increments `BOMB_DROPS`
    /// on Drop. Inside `Holder` it lives behind `ManuallyDrop<Box<
    /// Bomb>>` so the test can prove the manual-drop discipline
    /// hits exactly once.
    pub struct Bomb {
        pub payload: u64,
    }

    impl Drop for Bomb {
        fn drop(&mut self) {
            BOMB_DROPS.fetch_add(1, Ordering::SeqCst);
        }
    }

    /// Explicit drop-state machine. The compiler erases the
    /// distinction between `Initialized` (manual drop owes work)
    /// and `ManuallyDropped` (manual drop completed) -- this enum
    /// re-introduces it at runtime so `Holder::drop` can branch
    /// correctly.
    #[derive(Debug, PartialEq, Eq)]
    enum DropState {
        /// Pre-construction or post-take: the `ManuallyDrop` slot
        /// MUST NOT be read or dropped. Reserved for a future
        /// builder pattern; the current `new` always transitions
        /// directly to `Initialized`. Kept in the enum so the
        /// state machine is documented exhaustively at the type
        /// level.
        #[allow(dead_code)]
        NotInitialized,
        /// Post-construction, pre-dispose: the slot holds a valid
        /// `Box<Bomb>` and the manual drop owes work.
        Initialized,
        /// Post-dispose: the manual drop has run; the slot is
        /// logically empty and MUST NOT be re-dropped.
        ManuallyDropped,
    }

    /// `Holder` is the soundness-boundary type. Field privacy plus
    /// `DropState` together prove `ManuallyDrop::drop` runs exactly
    /// once -- compiler-checked, not comment-checked.
    pub struct Holder {
        inner: ManuallyDrop<Box<Bomb>>,
        state: DropState,
    }

    impl Holder {
        /// Construct with a fresh `Bomb`. Transitions
        /// `NotInitialized -> Initialized`.
        pub fn new(payload: u64) -> Self {
            Self { inner: ManuallyDrop::new(Box::new(Bomb { payload })), state: DropState::Initialized }
        }

        /// Read the payload while in `Initialized` state. Returns
        /// `None` once `dispose` has run, preventing read-after-
        /// drop on the wrapper.
        pub fn peek(&self) -> Option<u64> {
            match self.state {
                DropState::Initialized => Some(self.inner.payload),
                DropState::NotInitialized | DropState::ManuallyDropped => None,
            }
        }

        /// Drop the inner Bomb explicitly. Consumes `self` so the
        /// state transition is one-shot and the `Holder` cannot be
        /// observed in `ManuallyDropped` state from the outside.
        ///
        /// Transitions `Initialized -> ManuallyDropped`. Subsequent
        /// implicit Drop is a no-op because `state ==
        /// ManuallyDropped` short-circuits the manual drop.
        ///
        /// Returns the payload value so callers can confirm the
        /// drop happened (not strictly necessary for soundness;
        /// useful for tests).
        pub fn dispose(mut self) -> u64 {
            // SAFETY: `state == Initialized` is enforced by the
            // type system: only `Self::new` sets `Initialized`,
            // and `dispose` is the only consumer that transitions
            // away from it. The match arm pattern-binds the
            // payload BEFORE running ManuallyDrop::drop so the
            // returned value is not a read-after-drop.
            assert_eq!(self.state, DropState::Initialized, "dispose called in wrong state");
            let payload = self.inner.payload;
            // SAFETY: state guarantees the slot holds a valid
            // `Box<Bomb>` that has not been dropped. The
            // subsequent `state = ManuallyDropped` prevents the
            // implicit Drop impl from running the manual drop a
            // second time.
            unsafe {
                ManuallyDrop::drop(&mut self.inner);
            }
            self.state = DropState::ManuallyDropped;
            payload
        }
    }

    impl Drop for Holder {
        fn drop(&mut self) {
            match self.state {
                DropState::Initialized => {
                    // Panic-safety path: construction succeeded
                    // and `dispose` did not run (e.g. we are
                    // unwinding through Holder's scope). Drop the
                    // inner Bomb exactly once here.
                    //
                    // SAFETY: state guarantees the slot is a valid
                    // `Box<Bomb>` that has not been dropped. After
                    // this call we set state to `ManuallyDropped`
                    // so any future Drop call (which the type
                    // system prevents anyway) is a no-op.
                    unsafe {
                        ManuallyDrop::drop(&mut self.inner);
                    }
                    self.state = DropState::ManuallyDropped;
                }
                DropState::ManuallyDropped => {
                    // Normal-exit path: `dispose` already ran the
                    // manual drop. Implicit Drop is a no-op.
                }
                DropState::NotInitialized => {
                    // Reserved for a future builder pattern. The
                    // current `new` always transitions to
                    // Initialized, so this arm is unreachable in
                    // practice; the explicit match documents the
                    // exhaustive state machine.
                }
            }
        }
    }
}

use canary::{BOMB_DROPS, Bomb, Holder};
use std::sync::Mutex;

/// `cargo test` runs tests in parallel by default. Every test in
/// this module mutates the shared `BOMB_DROPS` counter, so they
/// MUST hold this mutex for the duration of their body. Without
/// it, a sibling test's increment would inflate the count and
/// break the assertion.
static DROP_GUARD: Mutex<()> = Mutex::new(());

fn lock() -> std::sync::MutexGuard<'static, ()> {
    DROP_GUARD.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[test]
fn dispose_runs_manual_drop_exactly_once() {
    let _g = lock();
    let before = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    let h = Holder::new(0xABCD);
    assert_eq!(h.peek(), Some(0xABCD));
    let payload = h.dispose();
    assert_eq!(payload, 0xABCD);
    let after = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(after - before, 1, "dispose must run Bomb::drop exactly once");
}

#[test]
fn implicit_drop_after_dispose_is_noop() {
    let _g = lock();
    let before = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    {
        let h = Holder::new(0x1234);
        let _ = h.dispose();
        // After dispose, h is consumed (move semantics); the type
        // system guarantees no further access. Reaching the
        // end-of-block here exercises the absence of any
        // additional implicit Drop on the same Holder.
    }
    let after = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(after - before, 1, "no second drop after dispose");
}

#[test]
fn dropping_without_dispose_runs_manual_drop_once() {
    let _g = lock();
    let before = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    {
        let _h = Holder::new(0xFEED);
        // No dispose call: the implicit Drop impl takes the
        // Initialized branch and runs ManuallyDrop::drop once.
    }
    let after = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(after - before, 1, "implicit Drop must run Bomb::drop exactly once");
}

#[test]
fn panic_during_use_unwinds_through_drop_and_runs_manual_drop_once() {
    let _g = lock();
    let before = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    let result = std::panic::catch_unwind(|| {
        let h = Holder::new(0xDEAD);
        assert_eq!(h.peek(), Some(0xDEAD));
        panic!("simulated failure before dispose");
        // Unreachable: but if we reached `h.dispose()`, the
        // counter would still be 1 -- this test proves the
        // panic-unwind path produces the same single-drop result.
        #[allow(unreachable_code)]
        h.dispose();
    });
    assert!(result.is_err(), "panic must propagate out of catch_unwind");
    let after = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(after - before, 1, "panic-unwind must still run Bomb::drop exactly once");
}

#[test]
fn peek_after_dispose_is_impossible_at_type_level() {
    let _g = lock();
    let h = Holder::new(0x42);
    // dispose consumes self, so the borrow checker rejects any
    // subsequent use of `h`. This test exists as a code-level
    // assertion that the API shape prevents read-after-drop by
    // move semantics, not by runtime checks.
    let _ = h.dispose();
    // Uncommenting the next line MUST fail to compile:
    // let _stale = h.peek();
}

#[test]
fn bomb_type_drop_counter_is_self_consistent() {
    let _g = lock();
    // Independent baseline: dropping a Bomb directly increments
    // the counter exactly once. This pins down the test
    // infrastructure -- if this fails, every assertion above is
    // suspect.
    let before = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    {
        let _b = Bomb { payload: 1 };
    }
    let after = BOMB_DROPS.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(after - before, 1);
}
