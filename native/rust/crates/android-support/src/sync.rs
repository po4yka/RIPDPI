// Conditional sync primitive re-exports for loom testing.
// Under `--features loom`, loom intercepts all atomic/mutex operations for
// exhaustive interleaving exploration. On 64-bit loom hosts AtomicUsize is
// 64-bit, so it is a safe stand-in for AtomicU64.

#[cfg(feature = "loom")]
pub(crate) use loom::sync::{Arc, Mutex};
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::{Arc, Mutex};

#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::Ordering;
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::Ordering;

// Under loom, AtomicUsize is used in place of AtomicU64 (loom does not provide
// AtomicU64). `fetch_add_u64` normalises the return value to `u64` so callers
// never observe the internal `usize` type.
#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::AtomicUsize as AtomicU64;
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::AtomicU64;

/// Calls `fetch_add` on the provided atomic and returns the result as `u64`.
///
/// This wrapper exists solely to paper over the fact that under loom the atomic
/// is `AtomicUsize` (whose `fetch_add` returns `usize`), while the production
/// path uses `AtomicU64` (which returns `u64` directly).
#[inline]
pub(crate) fn fetch_add_u64(atomic: &AtomicU64, val: u64, order: Ordering) -> u64 {
    #[cfg(feature = "loom")]
    {
        atomic.fetch_add(val as usize, order) as u64
    }
    #[cfg(not(feature = "loom"))]
    {
        atomic.fetch_add(val, order)
    }
}
