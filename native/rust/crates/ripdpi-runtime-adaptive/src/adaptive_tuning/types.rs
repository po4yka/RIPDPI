use std::net::SocketAddr;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum AdaptiveFlowKind {
    TcpTls,
    TcpOther,
    UdpQuic,
    UdpOther,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum AdaptivePlannerTarget {
    Host(String),
    Address(SocketAddr),
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(super) struct AdaptivePlannerKey {
    pub(super) network_scope_key: String,
    pub(super) group_index: usize,
    pub(super) flow_kind: AdaptiveFlowKind,
    pub(super) target: AdaptivePlannerTarget,
}
