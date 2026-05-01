use std::sync::Arc;
use std::task::{RawWaker, RawWakerVTable, Waker};
use std::thread;

/// Build a `Waker` whose `wake` calls `Thread::unpark` on the supplied
/// thread handle. The thread handle is held inside an `Arc`, refcounted via
/// the `RawWaker` vtable. Used by [`crate::ring::block_on_completion`] to bridge the
/// `Future` poll loop to `std::thread::park` without depending on tokio.
pub(crate) fn thread_waker(thread: thread::Thread) -> Waker {
    let arc = Arc::new(thread);
    let raw = RawWaker::new(Arc::into_raw(arc).cast(), &VTABLE);
    // SAFETY: the vtable functions uphold the RawWaker contract -- clone
    // increments the refcount, wake/wake_by_ref consume or borrow, drop
    // decrements. The data pointer is always a live `Arc<Thread>` raw
    // pointer.
    unsafe { Waker::from_raw(raw) }
}

unsafe fn clone_arc(data: *const ()) -> RawWaker {
    // SAFETY: `data` was created by `Arc::into_raw` for an `Arc<Thread>` and
    // remains live for the duration of this vtable callback.
    let arc = unsafe { Arc::from_raw(data.cast::<thread::Thread>()) };
    let cloned = Arc::clone(&arc);
    // Don't drop the original; reconstitute it as a raw pointer.
    let _ = Arc::into_raw(arc);
    RawWaker::new(Arc::into_raw(cloned).cast(), &VTABLE)
}

unsafe fn wake_arc(data: *const ()) {
    // SAFETY: `wake` consumes the raw waker, so taking ownership of the
    // `Arc<Thread>` exactly once satisfies the RawWaker contract.
    let arc = unsafe { Arc::from_raw(data.cast::<thread::Thread>()) };
    arc.unpark();
    // arc dropped here, decrementing refcount.
}

unsafe fn wake_arc_by_ref(data: *const ()) {
    // SAFETY: `wake_by_ref` borrows the raw waker. Reconstituting the Arc
    // after unparking preserves ownership for the original holder.
    let arc = unsafe { Arc::from_raw(data.cast::<thread::Thread>()) };
    arc.unpark();
    let _ = Arc::into_raw(arc);
}

unsafe fn drop_arc(data: *const ()) {
    // SAFETY: `drop` consumes one raw waker reference created by
    // `Arc::into_raw`, decrementing exactly one refcount.
    unsafe {
        drop(Arc::from_raw(data.cast::<thread::Thread>()));
    }
}

const VTABLE: RawWakerVTable = RawWakerVTable::new(clone_arc, wake_arc, wake_arc_by_ref, drop_arc);

#[cfg(test)]
mod tests {
    use std::thread;
    use std::time::{Duration, Instant};

    use super::*;

    /// `thread_waker` must build a `Waker` whose `wake_by_ref` unparks the
    /// originating thread. We exercise the unpark-token semantics directly:
    /// the wake fires before the park, so park returns immediately.
    #[test]
    fn thread_waker_wake_by_ref_unparks_originator() {
        let waker = thread_waker(thread::current());
        // Wake before park; the unpark token should make park return now.
        waker.wake_by_ref();
        let start = Instant::now();
        thread::park_timeout(Duration::from_millis(500));
        assert!(
            start.elapsed() < Duration::from_millis(250),
            "park_timeout did not return promptly after pre-wake (took {:?})",
            start.elapsed()
        );
    }

    /// A consuming `wake()` must also unpark the originating thread.
    #[test]
    fn thread_waker_wake_consumes_and_unparks() {
        let waker = thread_waker(thread::current());
        waker.wake();
        let start = Instant::now();
        thread::park_timeout(Duration::from_millis(500));
        assert!(
            start.elapsed() < Duration::from_millis(250),
            "consuming wake did not unpark in time (took {:?})",
            start.elapsed()
        );
    }

    /// Cloning a thread-backed Waker must yield an independent waker that
    /// also unparks the originator. After dropping the clone the original
    /// must still work.
    #[test]
    fn thread_waker_clone_independently_unparks() {
        let waker = thread_waker(thread::current());
        let cloned = waker.clone();
        drop(waker);
        cloned.wake();
        let start = Instant::now();
        thread::park_timeout(Duration::from_millis(500));
        assert!(
            start.elapsed() < Duration::from_millis(250),
            "cloned waker did not unpark in time (took {:?})",
            start.elapsed()
        );
    }
}
