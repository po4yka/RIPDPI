use ripdpi_config::{DesyncGroup, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};

use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

use super::candidates::{
    quic_fake_profile_candidates, split_offset_candidates, tls_record_offset_candidates, tlsrandrec_profile_candidates,
    udp_burst_profile_candidates,
};
use super::feedback;
use super::key::shuffled_dimensions;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ChoiceState<T> {
    pub(super) candidates: Vec<T>,
    pub(super) candidate_index: usize,
    pub(super) pinned: Option<T>,
    pub(super) cooldown_until_ms: Vec<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct AdaptivePlannerState {
    pub(super) split_offset_base: Option<ChoiceState<OffsetBase>>,
    pub(super) tls_record_offset_base: Option<ChoiceState<OffsetBase>>,
    pub(super) tlsrandrec_profile: Option<ChoiceState<AdaptiveTlsRandRecProfile>>,
    pub(super) udp_burst_profile: Option<ChoiceState<AdaptiveUdpBurstProfile>>,
    pub(super) quic_fake_profile: Option<ChoiceState<QuicFakeProfile>>,
    pub(super) dimension_order: Vec<usize>,
    pub(super) dimension_cursor: usize,
}

impl AdaptivePlannerState {
    pub(super) fn new(seed: u64) -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            dimension_order: shuffled_dimensions(seed),
            dimension_cursor: 0,
        }
    }

    pub(super) fn sync_tcp_candidates(&mut self, group: &DesyncGroup, payload: &[u8]) {
        let tls_payload = is_tls_client_hello_payload(payload);
        sync_choice(&mut self.split_offset_base, split_offset_candidates(group, tls_payload));
        sync_choice(&mut self.tls_record_offset_base, tls_record_offset_candidates(group));
        sync_choice(&mut self.tlsrandrec_profile, tlsrandrec_profile_candidates(group));
        self.udp_burst_profile = None;
        self.quic_fake_profile = None;
    }

    pub(super) fn sync_udp_candidates(&mut self, group: &DesyncGroup, payload: &[u8]) {
        sync_choice(&mut self.udp_burst_profile, udp_burst_profile_candidates(group));
        sync_choice(&mut self.quic_fake_profile, quic_fake_profile_candidates(group, payload));
        self.split_offset_base = None;
        self.tls_record_offset_base = None;
        self.tlsrandrec_profile = None;
    }

    pub(super) fn current_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base.as_ref().and_then(ChoiceState::current),
            tls_record_offset_base: self.tls_record_offset_base.as_ref().and_then(ChoiceState::current),
            tlsrandrec_profile: self.tlsrandrec_profile.as_ref().and_then(ChoiceState::current),
            udp_burst_profile: self.udp_burst_profile.as_ref().and_then(ChoiceState::current),
            quic_fake_profile: self.quic_fake_profile.as_ref().and_then(ChoiceState::current),
            entropy_mode: None,
        }
    }

    pub(super) fn note_success(&mut self) {
        feedback::note_success(self);
    }

    pub(super) fn note_failure(&mut self) {
        feedback::note_failure(self);
    }

    #[cfg(test)]
    pub(super) fn advance_dimension(&mut self, dimension: usize, now_ms: u64) -> bool {
        feedback::advance_dimension(self, dimension, now_ms)
    }
}
impl<T> ChoiceState<T>
where
    T: Copy + Eq,
{
    pub(super) fn new(candidates: Vec<T>) -> Self {
        let cooldown_until_ms = vec![0; candidates.len()];
        Self { candidates, candidate_index: 0, pinned: None, cooldown_until_ms }
    }

    pub(super) fn current(&self) -> Option<T> {
        self.pinned.or_else(|| self.candidates.get(self.candidate_index).copied())
    }
}

fn sync_choice<T>(slot: &mut Option<ChoiceState<T>>, candidates: Vec<T>)
where
    T: Copy + Eq,
{
    if candidates.is_empty() {
        *slot = None;
        return;
    }
    match slot {
        Some(state) if state.candidates == candidates => {
            state.cooldown_until_ms.resize(state.candidates.len(), 0);
        }
        Some(state) => *state = ChoiceState::new(candidates),
        None => *slot = Some(ChoiceState::new(candidates)),
    }
}
