//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

#![forbid(unsafe_code)]

mod adaptive;
mod policy;
mod snapshot;

pub mod snapshots {
    pub use crate::snapshot::{
        AdaptiveDecisionSnapshot, DesyncDecisionSnapshot, DirectPathDnsMode, DirectPathQuicMode, DnsDecisionSnapshot,
        ProxyDecisionSnapshot, QuicDecisionSnapshot, RelayDecisionSnapshot, RoutingDecisionSnapshot,
        RuntimeDecisionSnapshot, StrategyPackDecisionSnapshot, UiDecisionSnapshot, WarpDecisionSnapshot,
    };
}

pub mod adaptive_ports {
    pub use crate::adaptive::{
        AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargetSuppressionReason,
        PreferredTargets, RetryPacingPort,
    };
}

pub mod policy_ports {
    pub use crate::policy::{
        ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, DnsTamperingEvidence, ExtractedHost,
        GeoMatcher, HostAutolearnEvent, HostAutolearnState, HostSource, PolicyPort, RetrySelectionPenalty,
        RouteAdvance, TransportProtocol,
    };
}
