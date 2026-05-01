mod detect;
mod edit;
mod mutation;
#[cfg(test)]
mod tests;

pub use detect::*;
pub use mutation::*;

#[cfg(test)]
use edit::*;

pub const TLS_RECORD_HEADER_LEN: usize = 5;
