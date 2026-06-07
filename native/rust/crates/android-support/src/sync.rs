// Conditional sync primitive re-exports for loom testing.
// Under `--features loom`, loom intercepts all atomic/mutex operations for
// exhaustive interleaving exploration. On 64-bit loom hosts AtomicUsize is
// 64-bit, so it is a safe stand-in for AtomicU64.

#[cfg(feature = "loom")]
pub(crate) use loom::sync::Mutex;
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::Mutex;

#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::Ordering;
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::Ordering;

// Under loom, AtomicUsize is used in place of AtomicU64 (loom does not provide
// AtomicU64). The helpers below normalise values to `u64` so callers never
// observe the internal `usize` type.
#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::AtomicUsize as AtomicU64;
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::AtomicU64;

#[inline]
pub(crate) fn load_u64(atomic: &AtomicU64, order: Ordering) -> u64 {
    #[cfg(feature = "loom")]
    {
        atomic.load(order) as u64
    }
    #[cfg(not(feature = "loom"))]
    {
        atomic.load(order)
    }
}

#[inline]
pub(crate) fn store_u64(atomic: &AtomicU64, val: u64, order: Ordering) {
    #[cfg(feature = "loom")]
    {
        atomic.store(val as usize, order);
    }
    #[cfg(not(feature = "loom"))]
    {
        atomic.store(val, order);
    }
}
