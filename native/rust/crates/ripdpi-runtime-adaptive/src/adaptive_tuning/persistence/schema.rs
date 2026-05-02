mod offset_base;
mod quic_fake_profile;
mod stored;
mod tlsrandrec_profile;
mod udp_burst_profile;

use crate::adaptive_tuning::key::shuffled_dimensions;
use crate::adaptive_tuning::state::{AdaptivePlannerState, ChoiceState};

use quic_fake_profile::restore_quic_fake_profile;
use stored::{
    StoredAdaptiveTlsRandRecProfile, StoredAdaptiveUdpBurstProfile, StoredChoiceState, StoredQuicFakeProfile,
};
use tlsrandrec_profile::restore_tlsrandrec_profile;
use udp_burst_profile::restore_udp_burst_profile;

pub(in crate::adaptive_tuning) use offset_base::restore_offset_base;
pub(in crate::adaptive_tuning) use stored::StoredOffsetBase;
pub(super) use stored::{
    StoredAdaptiveNetworkScope, StoredAdaptivePlannerEntry, StoredAdaptivePlannerStore, ADAPTIVE_TUNING_STORE_VERSION,
};

impl AdaptivePlannerState {
    pub(super) fn to_persisted(&self) -> stored::StoredAdaptivePlannerState {
        stored::StoredAdaptivePlannerState {
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

    pub(super) fn from_persisted(state: stored::StoredAdaptivePlannerState, seed: u64) -> Self {
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
