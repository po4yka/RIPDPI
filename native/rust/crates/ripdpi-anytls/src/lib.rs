//! AnyTLS outbound client support.

pub mod frame;
pub mod padding;
pub mod session;

pub use padding::DEFAULT_PADDING_SCHEME;
