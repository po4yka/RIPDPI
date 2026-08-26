mod fingerprint_catalog;
mod http_classification;
mod http_parse;
mod response_fingerprint;
mod signal_mapping;
mod signal_types;

pub use fingerprint_catalog::{
    BlockpageFingerprint, FingerprintLocation, PatternType, bundled_blockpage_fingerprints,
    load_blockpage_fingerprints, load_blockpage_fingerprints_from_csv,
};
pub use http_classification::classify_http_response_block;
pub(crate) use http_classification::match_body_keyword;
pub use response_fingerprint::match_blockpage_response;
pub use signal_mapping::{block_signal_from_failure, block_signal_from_failure_with_context};
pub use signal_types::{BlockSignal, BlockSignalObservation};

#[cfg(test)]
mod tests;
