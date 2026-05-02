use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::adaptive_tuning::types::{AdaptiveFlowKind, AdaptivePlannerTarget};

pub(in crate::adaptive_tuning::persistence) const ADAPTIVE_TUNING_STORE_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(in crate::adaptive_tuning) enum StoredOffsetBase {
    Abs,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    AutoBalanced,
    AutoHost,
    AutoMidSld,
    AutoEndHost,
    AutoMethod,
    AutoSniExt,
    AutoExtLen,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(in crate::adaptive_tuning::persistence::schema) enum StoredAdaptiveTlsRandRecProfile {
    Balanced,
    Tight,
    Wide,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(in crate::adaptive_tuning::persistence::schema) enum StoredAdaptiveUdpBurstProfile {
    Balanced,
    Conservative,
    Aggressive,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(in crate::adaptive_tuning::persistence::schema) enum StoredQuicFakeProfile {
    Disabled,
    CompatDefault,
    RealisticInitial,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(in crate::adaptive_tuning::persistence::schema) struct StoredChoiceState<T> {
    pub(in crate::adaptive_tuning::persistence::schema) candidates: Vec<T>,
    pub(in crate::adaptive_tuning::persistence::schema) candidate_index: usize,
    pub(in crate::adaptive_tuning::persistence::schema) pinned: Option<T>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) cooldown_until_ms: Vec<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(in crate::adaptive_tuning::persistence) struct StoredAdaptivePlannerState {
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) split_offset_base: Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) tls_record_offset_base:
        Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) tlsrandrec_profile:
        Option<StoredChoiceState<StoredAdaptiveTlsRandRecProfile>>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) udp_burst_profile:
        Option<StoredChoiceState<StoredAdaptiveUdpBurstProfile>>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) quic_fake_profile:
        Option<StoredChoiceState<StoredQuicFakeProfile>>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) dimension_order: Vec<usize>,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence::schema) dimension_cursor: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(in crate::adaptive_tuning::persistence) struct StoredAdaptivePlannerEntry {
    pub(in crate::adaptive_tuning::persistence) group_index: usize,
    pub(in crate::adaptive_tuning::persistence) flow_kind: AdaptiveFlowKind,
    pub(in crate::adaptive_tuning::persistence) target: AdaptivePlannerTarget,
    pub(in crate::adaptive_tuning::persistence) state: StoredAdaptivePlannerState,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(in crate::adaptive_tuning::persistence) struct StoredAdaptiveNetworkScope {
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence) entries: Vec<StoredAdaptivePlannerEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(in crate::adaptive_tuning::persistence) struct StoredAdaptivePlannerStore {
    pub(in crate::adaptive_tuning::persistence) version: u32,
    pub(in crate::adaptive_tuning::persistence) fingerprint: String,
    #[serde(default)]
    pub(in crate::adaptive_tuning::persistence) scopes: BTreeMap<String, StoredAdaptiveNetworkScope>,
}
