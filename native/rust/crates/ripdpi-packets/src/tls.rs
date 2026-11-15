mod alerts;
mod detect;
mod edit;
mod mutation;
mod reassembled;
mod records;
#[cfg(test)]
mod records_tests;
#[cfg(test)]
mod tests;

pub use alerts::{TlsAlertPrefix, TlsAlertPrefixStatus, parse_tls_alert_prefix};
pub use detect::*;
pub use mutation::*;
pub use reassembled::*;

#[cfg(test)]
use edit::*;

pub const TLS_RECORD_HEADER_LEN: usize = 5;

const MAX_CLASSIFIER_TLS_BYTES: usize = 65_536;
