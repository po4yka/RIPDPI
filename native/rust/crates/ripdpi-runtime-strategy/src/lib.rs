#![forbid(unsafe_code)]

pub mod outbound_failover;
pub mod profiles;
pub mod scoring;
pub mod strategy_evolver;

pub use outbound_failover::{
    FailoverConfig, FailoverState, FailoverTrigger, OutboundFailover, OutboundRole, ProbeOutcome, RoleHealth,
    UdpViability,
};
pub use strategy_evolver::*;
