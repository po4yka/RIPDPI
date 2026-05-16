#![forbid(unsafe_code)]

pub mod block_detection;
mod connection_freeze;
mod dns;
pub mod field_classifier;
mod http;
mod quic;
mod strategy_execution;
mod tls;
mod transport;
mod transport_policy_cache;
mod types;

pub use block_detection::{
    block_signal_from_failure, bundled_blockpage_fingerprints, classify_http_response_block,
    load_blockpage_fingerprints, match_blockpage_response, BlockSignal, BlockSignalObservation, BlockpageFingerprint,
    FingerprintLocation, PatternType,
};
pub use http::classify_http_blockpage;
pub use quic::classify_quic_probe;
pub use strategy_execution::classify_strategy_execution_failure;
pub use tls::{classify_redirect_failure, classify_tls_alert, classify_tls_handshake_failure};
pub use transport::classify_transport_error;
pub use types::{
    ArmGate, ClassifiedFailure, FailureAction, FailureClass, FailureEvidence, FailureStage, IpBlockSuspectVerdict,
    IpBlockVerdict,
};
pub use {
    connection_freeze::classify_connection_freeze,
    dns::confirm_dns_tampering,
    transport_policy_cache::{
        AccessTypeTag, AtomicFile, CachedTransportPolicy, NetProfileKey, TransportPolicyCache, TransportPolicyCacheKey,
        DEFAULT_TTL_SECS,
    },
};
