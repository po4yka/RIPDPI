//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

mod snapshot;

pub mod snapshots {
    pub use crate::snapshot::{
        AdaptiveDecisionSnapshot, DesyncDecisionSnapshot, DirectPathDnsMode, DirectPathQuicMode, DnsDecisionSnapshot,
        ProxyDecisionSnapshot, QuicDecisionSnapshot, RelayDecisionSnapshot, RoutingDecisionSnapshot,
        RuntimeDecisionSnapshot, StrategyPackDecisionSnapshot, UiDecisionSnapshot, WarpDecisionSnapshot,
    };
}

pub mod adaptive_ports {
    pub use ripdpi_runtime_adaptive::{
        AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargets, RetryPacingPort,
    };
}

pub mod policy_ports {
    pub use ripdpi_runtime_policy::{
        ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, DnsTamperingEvidence, ExtractedHost,
        HostAutolearnEvent, HostAutolearnState, HostSource, PolicyPort, RetrySelectionPenalty, RouteAdvance,
        TransportProtocol,
    };
}
