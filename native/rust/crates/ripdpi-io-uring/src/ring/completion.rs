use std::collections::HashMap;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};

use crate::bufpool::BufferHandle;

/// Completion result delivered back to the caller.
pub struct CompletionResult {
    /// io_uring result code (bytes transferred, or negative errno).
    pub result: i32,
    /// CQE flags (check for `IORING_CQE_F_NOTIF`, `IORING_CQE_F_MORE`).
    pub flags: u32,
    buffer: Option<BufferHandle>,
}

impl CompletionResult {
    pub(crate) fn plain(result: i32, flags: u32) -> Self {
        Self { result, flags, buffer: None }
    }

    pub(crate) fn with_buffer(result: i32, flags: u32, buffer: BufferHandle) -> Self {
        Self { result, flags, buffer: Some(buffer) }
    }

    /// Recover the registered-buffer lease returned by a fixed-buffer operation.
    pub fn into_buffer(self) -> Option<BufferHandle> {
        self.buffer
    }
}

/// A future that resolves when the io_uring CQE for the associated
/// submission arrives.
pub struct CompletionFuture {
    token: u64,
    registry: Arc<CompletionRegistry>,
    finished: bool,
}

pub(crate) struct CompletionRegistry {
    slots: Mutex<HashMap<u64, WakerSlot>>,
}

enum WakerSlot {
    Pending(Option<Waker>),
    Ready(CompletionResult),
    Abandoned,
}

impl CompletionFuture {
    pub(crate) fn new(token: u64, registry: Arc<CompletionRegistry>) -> Self {
        Self { token, registry, finished: false }
    }
}

impl CompletionRegistry {
    pub(crate) fn new() -> Self {
        Self { slots: Mutex::new(HashMap::new()) }
    }

    /// Reserve a token before its submission can become visible to the kernel.
    pub(crate) fn begin(&self, token: u64) {
        let previous = self
            .slots
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(token, WakerSlot::Pending(None));
        debug_assert!(previous.is_none(), "completion token collision: {token}");
    }

    /// Register a waker for a given token. If the completion already arrived,
    /// returns the result immediately.
    pub(crate) fn register(&self, token: u64, waker: &Waker) -> Option<CompletionResult> {
        let mut slots = self.slots.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match slots.get_mut(&token) {
            Some(WakerSlot::Ready(_)) => {
                let Some(WakerSlot::Ready(result)) = slots.remove(&token) else { unreachable!() };
                Some(result)
            }
            Some(WakerSlot::Pending(current)) => {
                if current.as_ref().is_none_or(|registered| !registered.will_wake(waker)) {
                    *current = Some(waker.clone());
                }
                None
            }
            Some(WakerSlot::Abandoned) | None => None,
        }
    }

    /// Mark a future as abandoned without releasing kernel-visible resources.
    /// The matching CQE consumes the tombstone and drops its result/lease.
    pub(crate) fn abandon(&self, token: u64) {
        let mut slots = self.slots.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match slots.remove(&token) {
            Some(WakerSlot::Pending(_)) => {
                slots.insert(token, WakerSlot::Abandoned);
            }
            Some(WakerSlot::Ready(_) | WakerSlot::Abandoned) | None => {}
        }
    }

    /// Number of slots currently tracked. Test-only accessor.
    #[cfg(test)]
    pub(crate) fn slot_count(&self) -> usize {
        self.slots.lock().unwrap_or_else(std::sync::PoisonError::into_inner).len()
    }

    /// Deliver a completion. Wakes the waiting task if registered.
    ///
    /// Idempotent: the first completion for a tracked token wins. Unexpected
    /// duplicate or unknown CQEs are discarded instead of recreating a slot
    /// after its future has completed or been abandoned.
    pub(crate) fn complete(&self, token: u64, result: CompletionResult) {
        let waker = {
            let mut slots = self.slots.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            match slots.remove(&token) {
                Some(WakerSlot::Pending(waker)) => {
                    slots.insert(token, WakerSlot::Ready(result));
                    waker
                }
                Some(ready @ WakerSlot::Ready(_)) => {
                    slots.insert(token, ready);
                    None
                }
                Some(WakerSlot::Abandoned) | None => None,
            }
        };
        if let Some(waker) = waker {
            waker.wake();
        }
    }

    /// Resolve any registry entries not accounted for by the driver resource
    /// maps during teardown. Ready results are preserved; abandoned entries
    /// are removed without waking a task that no longer exists.
    pub(crate) fn fail_pending(&self, error: i32) {
        let wakers = {
            let mut slots = self.slots.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let mut wakers = Vec::new();
            slots.retain(|_, slot| match slot {
                WakerSlot::Pending(waker) => {
                    wakers.extend(waker.take());
                    *slot = WakerSlot::Ready(CompletionResult::plain(error, 0));
                    true
                }
                WakerSlot::Ready(_) => true,
                WakerSlot::Abandoned => false,
            });
            wakers
        };
        for waker in wakers {
            waker.wake();
        }
    }
}

impl std::future::Future for CompletionFuture {
    type Output = CompletionResult;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        match self.registry.register(self.token, cx.waker()) {
            Some(result) => {
                self.finished = true;
                Poll::Ready(result)
            }
            None => Poll::Pending,
        }
    }
}

impl Drop for CompletionFuture {
    fn drop(&mut self) {
        if !self.finished {
            self.registry.abandon(self.token);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::thread;
    use std::time::{Duration, Instant};

    use super::*;

    /// Drive a full register/complete handshake from two threads using the
    /// real `CompletionRegistry` and the park-based waker, mirroring the
    /// path `block_on_completion` would take. The polling thread must wake
    /// up promptly when the completion arrives from the driver-stand-in.
    #[test]
    fn registry_completion_unparks_polling_thread() {
        let registry = Arc::new(CompletionRegistry::new());
        let token = 0xDEAD_BEEF_u64;
        registry.begin(token);

        let driver_registry = Arc::clone(&registry);
        let driver_started = Arc::new(AtomicBool::new(false));
        let driver_started_clone = Arc::clone(&driver_started);

        // Driver-stand-in: fires `complete` after a brief delay so the
        // poller has time to register and park.
        let driver = thread::spawn(move || {
            driver_started_clone.store(true, Ordering::Release);
            thread::sleep(Duration::from_millis(50));
            driver_registry.complete(token, CompletionResult::plain(42, 0));
        });

        // Poller: register a thread-backed waker, then park until completion.
        let polling_thread = thread::current();
        let waker = waker_fn::waker_fn(move || polling_thread.unpark());
        let mut got: Option<CompletionResult> = None;
        let start = Instant::now();
        loop {
            match registry.register(token, &waker) {
                Some(result) => {
                    got = Some(result);
                    break;
                }
                None => {
                    thread::park_timeout(Duration::from_secs(2));
                }
            }
            if start.elapsed() > Duration::from_secs(3) {
                break;
            }
        }

        driver.join().expect("driver thread panicked");
        assert!(driver_started.load(Ordering::Acquire), "driver never started");
        let result = got.expect("poller never received completion");
        assert_eq!(result.result, 42);
        assert!(
            start.elapsed() < Duration::from_millis(500),
            "poller did not return promptly after completion (took {:?})",
            start.elapsed()
        );
    }

    /// A duplicate completion arriving before the future is polled must not
    /// overwrite the first result or strand an extra registry slot.
    #[test]
    fn duplicate_completion_before_poll_keeps_first_result_and_one_slot() {
        let registry = CompletionRegistry::new();
        let token = 0x1234_u64;
        registry.begin(token);

        // First CQE (the result CQE).
        registry.complete(token, CompletionResult::plain(100, 0));
        // Second CQE (the NOTIF CQE) for the same token, still unconsumed.
        registry.complete(token, CompletionResult::plain(200, 0x8 /* F_NOTIF */));

        // Only one slot is tracked despite two completions.
        assert_eq!(registry.slot_count(), 1, "duplicate completion must not strand a second slot");

        // Poll picks up the FIRST result and clears the slot.
        let noop = waker_fn::waker_fn(|| {});
        let got = registry.register(token, &noop).expect("ready result must be available");
        assert_eq!(got.result, 100, "first completion must win");
        assert_eq!(registry.slot_count(), 0, "slot must be cleared after the result is consumed");
    }

    /// F12 regression: a late completion arriving AFTER the result was already
    /// consumed re-stores a single result and never accumulates slots across
    /// repeated late deliveries (idempotent in aggregate).
    #[test]
    fn late_completion_after_consume_does_not_accumulate_slots() {
        let registry = CompletionRegistry::new();
        let token = 0x5678_u64;
        registry.begin(token);

        registry.complete(token, CompletionResult::plain(7, 0));
        let noop = waker_fn::waker_fn(|| {});
        let got = registry.register(token, &noop).expect("ready result");
        assert_eq!(got.result, 7);
        assert_eq!(registry.slot_count(), 0);

        // Late duplicates after the known token has been consumed are ignored.
        registry.complete(token, CompletionResult::plain(8, 0x8));
        registry.complete(token, CompletionResult::plain(9, 0x8));
        assert_eq!(registry.slot_count(), 0, "late duplicates must not recreate consumed slots");
    }

    #[test]
    fn dropped_future_keeps_tombstone_until_late_completion() {
        let registry = Arc::new(CompletionRegistry::new());
        let token = 0xCA11_u64;
        registry.begin(token);

        let future = CompletionFuture::new(token, Arc::clone(&registry));
        drop(future);
        assert_eq!(registry.slot_count(), 1, "abandoned operation must remain tracked until its CQE");

        registry.complete(token, CompletionResult::plain(1, 0));
        assert_eq!(registry.slot_count(), 0, "late CQE must consume the abandoned tombstone");
    }

    #[test]
    fn dropping_ready_future_releases_unconsumed_result() {
        let registry = Arc::new(CompletionRegistry::new());
        let token = 0xCA12_u64;
        registry.begin(token);
        let future = CompletionFuture::new(token, Arc::clone(&registry));

        registry.complete(token, CompletionResult::plain(1, 0));
        assert_eq!(registry.slot_count(), 1);
        drop(future);
        assert_eq!(registry.slot_count(), 0, "dropping the future must remove its ready result");
    }

    #[test]
    fn completion_wakes_outside_registry_lock() {
        let registry = Arc::new(CompletionRegistry::new());
        let token = 0xCA13_u64;
        registry.begin(token);

        let reentrant_registry = Arc::clone(&registry);
        let waker = waker_fn::waker_fn(move || {
            let _ = reentrant_registry.slot_count();
        });
        assert!(registry.register(token, &waker).is_none());

        registry.complete(token, CompletionResult::plain(1, 0));
        assert_eq!(registry.slot_count(), 1);
    }
}
