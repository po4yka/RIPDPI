// Conditional sync primitive re-exports for loom testing.
// Under `--features loom`, loom intercepts all atomic/mutex operations for
// exhaustive interleaving exploration.

#[allow(unused_imports)]
#[cfg(feature = "loom")]
pub(crate) use loom::sync::{Arc, Mutex, RwLock};
#[allow(unused_imports)]
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::{Arc, Mutex, RwLock};

#[allow(unused_imports)]
#[cfg(feature = "loom")]
pub(crate) use loom::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
#[allow(unused_imports)]
#[cfg(not(feature = "loom"))]
pub(crate) use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
