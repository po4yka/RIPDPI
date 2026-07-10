#![forbid(unsafe_code)]

pub mod block_detection;
mod confirm_good;
mod connection_concurrency;
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
    BlockSignal, BlockSignalObservation, BlockpageFingerprint, FingerprintLocation, PatternType,
    block_signal_from_failure, block_signal_from_failure_with_context, bundled_blockpage_fingerprints,
    classify_http_response_block, load_blockpage_fingerprints, match_blockpage_response,
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
    confirm_good::{
        CONFIRM_GOOD_EVIDENCE_WINDOW_MS, CONFIRM_GOOD_MAX_OBSERVATIONS, ConfirmGoodDpiAccumulator,
        ConfirmGoodDpiEvidence, ConfirmGoodEvidenceSource, ConfirmGoodFlowObservation, ConfirmGoodFlowSource,
        ConfirmGoodTerminalReason,
    },
    connection_concurrency::{
        CONNECTION_CONCURRENCY_CLASSIFIER_VERSION, ConnectionConcurrencyAssessment, ConnectionConcurrencyCell,
        ConnectionConcurrencyCellStatus, ConnectionConcurrencyEvidence, ConnectionConcurrencyVerdict,
        FailureEvidenceContext, classify_connection_concurrency_matrix,
    },
    connection_freeze::classify_connection_freeze,
    dns::confirm_dns_tampering,
    transport_policy_cache::{
        AccessTypeTag, AtomicFile, CachedTransportPolicy, DEFAULT_TTL_SECS, NetProfileKey, TransportPolicyCache,
        TransportPolicyCacheKey,
    },
};
