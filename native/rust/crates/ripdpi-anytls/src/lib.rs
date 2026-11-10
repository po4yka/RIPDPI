//! AnyTLS outbound client support.

#![forbid(unsafe_code)]

pub mod frame;
pub mod padding;
pub mod pin;
pub mod session;

pub use padding::DEFAULT_PADDING_SCHEME;
