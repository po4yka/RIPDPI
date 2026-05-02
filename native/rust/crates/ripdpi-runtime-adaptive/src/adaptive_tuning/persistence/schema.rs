use std::collections::BTreeMap;

use ripdpi_config::{OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use serde::{Deserialize, Serialize};

use crate::adaptive_tuning::key::shuffled_dimensions;
use crate::adaptive_tuning::state::{AdaptivePlannerState, ChoiceState};
use crate::adaptive_tuning::types::{AdaptiveFlowKind, AdaptivePlannerTarget};

pub(super) const ADAPTIVE_TUNING_STORE_VERSION: u32 = 1;

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
enum StoredAdaptiveTlsRandRecProfile {
    Balanced,
    Tight,
    Wide,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredAdaptiveUdpBurstProfile {
    Balanced,
    Conservative,
    Aggressive,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredQuicFakeProfile {
    Disabled,
    CompatDefault,
    RealisticInitial,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct StoredChoiceState<T> {
    candidates: Vec<T>,
    candidate_index: usize,
    pinned: Option<T>,
    #[serde(default)]
    cooldown_until_ms: Vec<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(super) struct StoredAdaptivePlannerState {
    #[serde(default)]
    split_offset_base: Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    tls_record_offset_base: Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    tlsrandrec_profile: Option<StoredChoiceState<StoredAdaptiveTlsRandRecProfile>>,
    #[serde(default)]
    udp_burst_profile: Option<StoredChoiceState<StoredAdaptiveUdpBurstProfile>>,
    #[serde(default)]
    quic_fake_profile: Option<StoredChoiceState<StoredQuicFakeProfile>>,
    #[serde(default)]
    dimension_order: Vec<usize>,
    #[serde(default)]
    dimension_cursor: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(super) struct StoredAdaptivePlannerEntry {
    pub(super) group_index: usize,
    pub(super) flow_kind: AdaptiveFlowKind,
    pub(super) target: AdaptivePlannerTarget,
    pub(super) state: StoredAdaptivePlannerState,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(super) struct StoredAdaptiveNetworkScope {
    #[serde(default)]
    pub(super) entries: Vec<StoredAdaptivePlannerEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(super) struct StoredAdaptivePlannerStore {
    pub(super) version: u32,
    pub(super) fingerprint: String,
    #[serde(default)]
    pub(super) scopes: BTreeMap<String, StoredAdaptiveNetworkScope>,
}

impl AdaptivePlannerState {
    pub(super) fn to_persisted(&self) -> StoredAdaptivePlannerState {
        StoredAdaptivePlannerState {
            split_offset_base: self
                .split_offset_base
                .as_ref()
                .map(|choice| store_choice(choice, StoredOffsetBase::from)),
            tls_record_offset_base: self
                .tls_record_offset_base
                .as_ref()
                .map(|choice| store_choice(choice, StoredOffsetBase::from)),
            tlsrandrec_profile: self
                .tlsrandrec_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredAdaptiveTlsRandRecProfile::from)),
            udp_burst_profile: self
                .udp_burst_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredAdaptiveUdpBurstProfile::from)),
            quic_fake_profile: self
                .quic_fake_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredQuicFakeProfile::from)),
            dimension_order: self.dimension_order.clone(),
            dimension_cursor: self.dimension_cursor,
        }
    }

    pub(super) fn from_persisted(state: StoredAdaptivePlannerState, seed: u64) -> Self {
        let dimension_order = if valid_dimension_order(&state.dimension_order) {
            state.dimension_order
        } else {
            shuffled_dimensions(seed)
        };
        let dimension_cursor = if state.dimension_cursor < dimension_order.len() { state.dimension_cursor } else { 0 };
        Self {
            split_offset_base: state.split_offset_base.and_then(|choice| load_choice(choice, restore_offset_base)),
            tls_record_offset_base: state
                .tls_record_offset_base
                .and_then(|choice| load_choice(choice, restore_offset_base)),
            tlsrandrec_profile: state
                .tlsrandrec_profile
                .and_then(|choice| load_choice(choice, restore_tlsrandrec_profile)),
            udp_burst_profile: state
                .udp_burst_profile
                .and_then(|choice| load_choice(choice, restore_udp_burst_profile)),
            quic_fake_profile: state
                .quic_fake_profile
                .and_then(|choice| load_choice(choice, restore_quic_fake_profile)),
            dimension_order,
            dimension_cursor,
        }
    }
}

fn store_choice<T, U>(choice: &ChoiceState<T>, map: impl Fn(T) -> U) -> StoredChoiceState<U>
where
    T: Copy + Eq,
{
    StoredChoiceState {
        candidates: choice.candidates.iter().copied().map(&map).collect(),
        candidate_index: choice.candidate_index,
        pinned: choice.pinned.map(&map),
        cooldown_until_ms: choice.cooldown_until_ms.clone(),
    }
}

fn load_choice<T, U>(choice: StoredChoiceState<U>, map: impl Fn(U) -> Option<T>) -> Option<ChoiceState<T>>
where
    T: Copy + Eq,
{
    let candidates = choice.candidates.into_iter().map(&map).collect::<Option<Vec<_>>>()?;
    if candidates.is_empty() {
        return None;
    }
    let mut cooldown_until_ms = choice.cooldown_until_ms;
    cooldown_until_ms.resize(candidates.len(), 0);
    cooldown_until_ms.truncate(candidates.len());
    Some(ChoiceState {
        candidates,
        candidate_index: if choice.candidate_index < cooldown_until_ms.len() { choice.candidate_index } else { 0 },
        pinned: match choice.pinned {
            Some(value) => Some(map(value)?),
            None => None,
        },
        cooldown_until_ms,
    })
}

fn valid_dimension_order(order: &[usize]) -> bool {
    if order.len() != 5 {
        return false;
    }
    let mut sorted = order.to_vec();
    sorted.sort_unstable();
    sorted == [0, 1, 2, 3, 4]
}

pub(in crate::adaptive_tuning) fn restore_offset_base(base: StoredOffsetBase) -> Option<OffsetBase> {
    Some(match base {
        StoredOffsetBase::Abs => OffsetBase::Abs,
        StoredOffsetBase::PayloadEnd => OffsetBase::PayloadEnd,
        StoredOffsetBase::PayloadMid => OffsetBase::PayloadMid,
        StoredOffsetBase::PayloadRand => OffsetBase::PayloadRand,
        StoredOffsetBase::Host => OffsetBase::Host,
        StoredOffsetBase::EndHost => OffsetBase::EndHost,
        StoredOffsetBase::HostMid => OffsetBase::HostMid,
        StoredOffsetBase::HostRand => OffsetBase::HostRand,
        StoredOffsetBase::Sld => OffsetBase::Sld,
        StoredOffsetBase::MidSld => OffsetBase::MidSld,
        StoredOffsetBase::EndSld => OffsetBase::EndSld,
        StoredOffsetBase::Method => OffsetBase::Method,
        StoredOffsetBase::ExtLen => OffsetBase::ExtLen,
        StoredOffsetBase::EchExt => OffsetBase::EchExt,
        StoredOffsetBase::SniExt => OffsetBase::SniExt,
        StoredOffsetBase::AutoBalanced => OffsetBase::AutoBalanced,
        StoredOffsetBase::AutoHost => OffsetBase::AutoHost,
        StoredOffsetBase::AutoMidSld => OffsetBase::AutoMidSld,
        StoredOffsetBase::AutoEndHost => OffsetBase::AutoEndHost,
        StoredOffsetBase::AutoMethod => OffsetBase::AutoMethod,
        StoredOffsetBase::AutoSniExt => OffsetBase::AutoSniExt,
        StoredOffsetBase::AutoExtLen => OffsetBase::AutoExtLen,
    })
}

fn restore_tlsrandrec_profile(profile: StoredAdaptiveTlsRandRecProfile) -> Option<AdaptiveTlsRandRecProfile> {
    Some(match profile {
        StoredAdaptiveTlsRandRecProfile::Balanced => AdaptiveTlsRandRecProfile::Balanced,
        StoredAdaptiveTlsRandRecProfile::Tight => AdaptiveTlsRandRecProfile::Tight,
        StoredAdaptiveTlsRandRecProfile::Wide => AdaptiveTlsRandRecProfile::Wide,
    })
}

fn restore_udp_burst_profile(profile: StoredAdaptiveUdpBurstProfile) -> Option<AdaptiveUdpBurstProfile> {
    Some(match profile {
        StoredAdaptiveUdpBurstProfile::Balanced => AdaptiveUdpBurstProfile::Balanced,
        StoredAdaptiveUdpBurstProfile::Conservative => AdaptiveUdpBurstProfile::Conservative,
        StoredAdaptiveUdpBurstProfile::Aggressive => AdaptiveUdpBurstProfile::Aggressive,
    })
}

fn restore_quic_fake_profile(profile: StoredQuicFakeProfile) -> Option<QuicFakeProfile> {
    Some(match profile {
        StoredQuicFakeProfile::Disabled => QuicFakeProfile::Disabled,
        StoredQuicFakeProfile::CompatDefault => QuicFakeProfile::CompatDefault,
        StoredQuicFakeProfile::RealisticInitial => QuicFakeProfile::RealisticInitial,
    })
}

impl From<OffsetBase> for StoredOffsetBase {
    fn from(base: OffsetBase) -> Self {
        match base {
            OffsetBase::Abs => Self::Abs,
            OffsetBase::PayloadEnd => Self::PayloadEnd,
            OffsetBase::PayloadMid => Self::PayloadMid,
            OffsetBase::PayloadRand => Self::PayloadRand,
            OffsetBase::Host => Self::Host,
            OffsetBase::EndHost => Self::EndHost,
            OffsetBase::HostMid => Self::HostMid,
            OffsetBase::HostRand => Self::HostRand,
            OffsetBase::Sld => Self::Sld,
            OffsetBase::MidSld => Self::MidSld,
            OffsetBase::EndSld => Self::EndSld,
            OffsetBase::Method => Self::Method,
            OffsetBase::ExtLen => Self::ExtLen,
            OffsetBase::EchExt => Self::EchExt,
            OffsetBase::SniExt => Self::SniExt,
            OffsetBase::AutoBalanced => Self::AutoBalanced,
            OffsetBase::AutoHost => Self::AutoHost,
            OffsetBase::AutoMidSld => Self::AutoMidSld,
            OffsetBase::AutoEndHost => Self::AutoEndHost,
            OffsetBase::AutoMethod => Self::AutoMethod,
            OffsetBase::AutoSniExt => Self::AutoSniExt,
            OffsetBase::AutoExtLen => Self::AutoExtLen,
        }
    }
}

impl From<AdaptiveTlsRandRecProfile> for StoredAdaptiveTlsRandRecProfile {
    fn from(profile: AdaptiveTlsRandRecProfile) -> Self {
        match profile {
            AdaptiveTlsRandRecProfile::Balanced => Self::Balanced,
            AdaptiveTlsRandRecProfile::Tight => Self::Tight,
            AdaptiveTlsRandRecProfile::Wide => Self::Wide,
        }
    }
}

impl From<AdaptiveUdpBurstProfile> for StoredAdaptiveUdpBurstProfile {
    fn from(profile: AdaptiveUdpBurstProfile) -> Self {
        match profile {
            AdaptiveUdpBurstProfile::Balanced => Self::Balanced,
            AdaptiveUdpBurstProfile::Conservative => Self::Conservative,
            AdaptiveUdpBurstProfile::Aggressive => Self::Aggressive,
        }
    }
}

impl From<QuicFakeProfile> for StoredQuicFakeProfile {
    fn from(profile: QuicFakeProfile) -> Self {
        match profile {
            QuicFakeProfile::Disabled => Self::Disabled,
            QuicFakeProfile::CompatDefault => Self::CompatDefault,
            QuicFakeProfile::RealisticInitial => Self::RealisticInitial,
            _ => Self::Disabled,
        }
    }
}
