use super::key::now_millis;
use super::state::{AdaptivePlannerState, ChoiceState};

pub(super) const ADAPTIVE_RETRY_WINDOW_MS: u64 = 15_000;

pub(super) fn note_success(state: &mut AdaptivePlannerState) {
    pin_choice(&mut state.split_offset_base);
    pin_choice(&mut state.tls_record_offset_base);
    pin_choice(&mut state.tlsrandrec_profile);
    pin_choice(&mut state.udp_burst_profile);
    pin_choice(&mut state.quic_fake_profile);
    clear_cooldowns(&mut state.split_offset_base);
    clear_cooldowns(&mut state.tls_record_offset_base);
    clear_cooldowns(&mut state.tlsrandrec_profile);
    clear_cooldowns(&mut state.udp_burst_profile);
    clear_cooldowns(&mut state.quic_fake_profile);
}

pub(super) fn note_failure(state: &mut AdaptivePlannerState) {
    let now_ms = now_millis();
    for offset in 0..state.dimension_order.len() {
        let position = (state.dimension_cursor + offset) % state.dimension_order.len();
        let dimension = state.dimension_order[position];
        if advance_dimension(state, dimension, now_ms) {
            state.dimension_cursor = (position + 1) % state.dimension_order.len();
            break;
        }
    }
}

pub(super) fn advance_dimension(state: &mut AdaptivePlannerState, dimension: usize, now_ms: u64) -> bool {
    match dimension {
        0 => advance_choice(&mut state.split_offset_base, now_ms),
        1 => advance_choice(&mut state.tls_record_offset_base, now_ms),
        2 => advance_choice(&mut state.tlsrandrec_profile, now_ms),
        3 => advance_choice(&mut state.udp_burst_profile, now_ms),
        4 => advance_choice(&mut state.quic_fake_profile, now_ms),
        _ => false,
    }
}

impl<T> ChoiceState<T>
where
    T: Copy + Eq,
{
    pub(super) fn note_success(&mut self) {
        self.pinned = self.current();
    }

    fn clear_cooldowns(&mut self) {
        self.cooldown_until_ms.fill(0);
    }

    pub(super) fn note_failure(&mut self, now_ms: u64) {
        self.pinned = None;
        if self.candidates.len() <= 1 {
            return;
        }
        if self.candidate_index < self.cooldown_until_ms.len() {
            self.cooldown_until_ms[self.candidate_index] = now_ms.saturating_add(ADAPTIVE_RETRY_WINDOW_MS);
        }
        for offset in 1..=self.candidates.len() {
            let candidate = (self.candidate_index + offset) % self.candidates.len();
            if self.cooldown_until_ms.get(candidate).copied().unwrap_or_default() <= now_ms {
                self.candidate_index = candidate;
                return;
            }
        }
        self.candidate_index = (self.candidate_index + 1) % self.candidates.len();
    }

    fn is_adaptive(&self) -> bool {
        self.candidates.len() > 1
    }
}

fn pin_choice<T>(slot: &mut Option<ChoiceState<T>>)
where
    T: Copy + Eq,
{
    if let Some(state) = slot.as_mut() {
        state.note_success();
    }
}

fn clear_cooldowns<T>(slot: &mut Option<ChoiceState<T>>)
where
    T: Copy + Eq,
{
    if let Some(state) = slot.as_mut() {
        state.clear_cooldowns();
    }
}

fn advance_choice<T>(slot: &mut Option<ChoiceState<T>>, now_ms: u64) -> bool
where
    T: Copy + Eq,
{
    let Some(state) = slot.as_mut() else {
        return false;
    };
    if !state.is_adaptive() {
        return false;
    }
    state.note_failure(now_ms);
    true
}
