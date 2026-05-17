use std::collections::BTreeMap;

use ripdpi_config::CacheEntry;
use ripdpi_failure_classifier::BlockSignal;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub(super) struct GroupPolicy {
    pub detect: u32,
    pub fail_count: i32,
    pub pri: i32,
}

#[derive(Debug, Clone)]
pub(super) struct CacheRecord {
    pub entry: CacheEntry,
    pub group_index: usize,
    pub attempted_mask: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub(super) struct LearnedGroupStats {
    pub success_count: u32,
    pub failure_count: u32,
    pub penalty_until_ms: u64,
    pub last_success_at_ms: u64,
    pub last_failure_at_ms: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub(super) struct LearnedHostRecord {
    pub preferred_groups: Vec<usize>,
    pub group_stats: BTreeMap<usize, LearnedGroupStats>,
    pub updated_at_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub blocked_until_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_blocked_at_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_block_signal: Option<BlockSignal>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_block_provider: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(super) struct LearnedNetworkScopeStore {
    #[serde(default)]
    pub hosts: BTreeMap<String, LearnedHostRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(super) struct LearnedHostStore {
    pub version: u32,
    pub fingerprint: String,
    #[serde(default)]
    pub scopes: BTreeMap<String, LearnedNetworkScopeStore>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(super) struct PendingBlockedHost {
    pub first_detected_at_ms: u64,
    pub count: u8,
    pub last_signal: Option<BlockSignal>,
    pub last_provider: Option<String>,
}

#[derive(Debug)]
pub(super) enum LoadLearnedHostStoreError {
    Invalidated,
    Io,
}
