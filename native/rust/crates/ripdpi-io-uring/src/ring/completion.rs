use std::collections::HashMap;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};

/// Completion result delivered back to the caller.
#[derive(Debug, Clone)]
pub struct CompletionResult {
    /// io_uring result code (bytes transferred, or negative errno).
    pub result: i32,
    /// CQE flags (check for `IORING_CQE_F_NOTIF`, `IORING_CQE_F_MORE`).
    pub flags: u32,
}

/// A future that resolves when the io_uring CQE for the associated
/// submission arrives.
pub struct CompletionFuture {
    token: u64,
    registry: Arc<CompletionRegistry>,
}

pub(crate) struct CompletionRegistry {
    slots: Mutex<HashMap<u64, WakerSlot>>,
}

enum WakerSlot {
    Waiting(Waker),
    Ready(CompletionResult),
}

impl CompletionFuture {
    pub(crate) fn new(token: u64, registry: Arc<CompletionRegistry>) -> Self {
        Self { token, registry }
    }
}

impl CompletionRegistry {
    pub(crate) fn new() -> Self {
        Self { slots: Mutex::new(HashMap::new()) }
    }

    /// Register a waker for a given token. If the completion already arrived,
    /// returns the result immediately.
    pub(crate) fn register(&self, token: u64, waker: &Waker) -> Option<CompletionResult> {
        let mut slots = self.slots.lock().ok()?;
        match slots.remove(&token) {
            Some(WakerSlot::Ready(result)) => Some(result),
            _ => {
                slots.insert(token, WakerSlot::Waiting(waker.clone()));
                None
            }
        }
    }

    /// Number of slots currently tracked (waiting or ready). Test-only accessor.
    #[cfg(test)]
    pub(crate) fn slot_count(&self) -> usize {
        self.slots.lock().map_or(0, |g| g.len())
    }

    /// Deliver a completion. Wakes the waiting task if registered.
    ///
    /// Idempotent: the **first** completion for a token wins. A second
    /// completion for the same token while its `Ready` result is still
    /// unconsumed is dropped rather than overwriting the first result. This
    /// matters for ops that legitimately raise more than one CQE per token —
    /// e.g. `IORING_OP_SEND_ZC` emits a result CQE followed by a distinct
    /// `IORING_CQE_F_NOTIF` CQE. Without this guard the second CQE would
    /// re-insert a `Ready` slot that no future will ever poll, stranding the
    /// registry entry (a slow leak). The inbound relay path no longer uses
    /// SEND_ZC, but keeping `complete` idempotent makes the registry safe for
    /// any future multi-CQE opcode.
    pub(crate) fn complete(&self, token: u64, result: CompletionResult) {
        if let Ok(mut slots) = self.slots.lock() {
            match slots.remove(&token) {
                Some(WakerSlot::Waiting(waker)) => {
                    slots.insert(token, WakerSlot::Ready(result));
                    waker.wake();
                }
                Some(ready @ WakerSlot::Ready(_)) => {
                    // A completion already arrived for this token and has not
                    // been consumed yet -- keep the first result, drop this one.
                    slots.insert(token, ready);
                }
                None => {
                    // Completion arrived before poll -- store it for pickup.
                    slots.insert(token, WakerSlot::Ready(result));
                }
            }
        }
    }
}

impl std::future::Future for CompletionFuture {
    type Output = CompletionResult;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        match self.registry.register(self.token, cx.waker()) {
            Some(result) => Poll::Ready(result),
            None => Poll::Pending,
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

        let driver_registry = Arc::clone(&registry);
        let driver_started = Arc::new(AtomicBool::new(false));
        let driver_started_clone = Arc::clone(&driver_started);

        // Driver-stand-in: fires `complete` after a brief delay so the
        // poller has time to register and park.
        let driver = thread::spawn(move || {
            driver_started_clone.store(true, Ordering::Release);
            thread::sleep(Duration::from_millis(50));
            driver_registry.complete(token, CompletionResult { result: 42, flags: 0 });
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

    /// F12 regression: a second completion for the same token (e.g. the
    /// `IORING_CQE_F_NOTIF` CQE that follows a `SEND_ZC` result CQE) arriving
    /// before the future is polled must NOT overwrite the first result and
    /// must NOT strand an extra registry slot. The first result wins and
    /// exactly one slot exists.
    #[test]
    fn duplicate_completion_before_poll_keeps_first_result_and_one_slot() {
        let registry = CompletionRegistry::new();
        let token = 0x1234_u64;

        // First CQE (the result CQE).
        registry.complete(token, CompletionResult { result: 100, flags: 0 });
        // Second CQE (the NOTIF CQE) for the same token, still unconsumed.
        registry.complete(token, CompletionResult { result: 200, flags: 0x8 /* F_NOTIF */ });

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

        registry.complete(token, CompletionResult { result: 7, flags: 0 });
        let noop = waker_fn::waker_fn(|| {});
        let got = registry.register(token, &noop).expect("ready result");
        assert_eq!(got.result, 7);
        assert_eq!(registry.slot_count(), 0);

        // A late duplicate (re-)stores one result; a second late duplicate is
        // dropped because a Ready slot already exists. Either way the slot
        // count never exceeds one.
        registry.complete(token, CompletionResult { result: 8, flags: 0x8 });
        registry.complete(token, CompletionResult { result: 9, flags: 0x8 });
        assert_eq!(registry.slot_count(), 1, "late duplicates must not accumulate slots");
    }
}
