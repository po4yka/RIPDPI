//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

pub use ripdpi_runtime_adaptive::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargets, RetryPacingPort,
};
pub use ripdpi_runtime_policy::{
    ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, DnsTamperingEvidence, ExtractedHost,
    HostAutolearnEvent, HostAutolearnState, HostSource, PolicyPort, RetrySelectionPenalty, RouteAdvance,
    TransportProtocol,
};
