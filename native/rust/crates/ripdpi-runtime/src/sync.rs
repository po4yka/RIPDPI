// Conditional sync primitive re-exports for loom testing.
// Under `--features loom`, loom intercepts all atomic/mutex operations for
// exhaustive interleaving exploration.

#[cfg(feature = "loom")]
pub(crate) use loom::sync::{Arc, Mutex};
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::{Arc, Mutex};

#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
