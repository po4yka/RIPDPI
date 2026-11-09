mod detect;
mod edit;
mod mutation;
mod reassembled;
#[cfg(test)]
mod tests;

pub use detect::*;
pub use mutation::*;
pub use reassembled::*;

#[cfg(test)]
use edit::*;

pub const TLS_RECORD_HEADER_LEN: usize = 5;
