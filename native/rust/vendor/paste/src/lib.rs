//! Preserve the `paste` macro API for dependencies that still use that name.

#![no_std]
#![forbid(unsafe_code)]

pub use pastey::{expr, item, paste};
