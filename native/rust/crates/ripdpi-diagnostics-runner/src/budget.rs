/// Attempt-budget caps for a single diagnostic run.
///
/// Breaching any one cap stops the run.  Callers can inspect [`BudgetTrip`]
/// to learn which cap fired.
#[derive(Debug, Clone)]
pub struct AttemptBudget {
    /// Maximum number of concurrently-active probe arms.
    pub max_active_arms: u32,
    /// Wall-clock limit for the whole run in milliseconds.
    pub max_elapsed_ms: u64,
    /// Maximum aggregate probe bytes transferred.
    pub max_probe_bytes: u64,
    /// When `true`, the run stops after the first successful probe is
    /// *confirmed* (i.e. one additional confirmation attempt is allowed
    /// before halting — the "confirm_once" rule).
    pub stop_on_first_stable_success: bool,
}

impl Default for AttemptBudget {
    fn default() -> Self {
        Self { max_active_arms: 5, max_elapsed_ms: 6_000, max_probe_bytes: 65_536, stop_on_first_stable_success: true }
    }
}

/// Which budget cap caused the run to stop.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BudgetTrip {
    MaxActiveArms,
    MaxElapsedMs,
    MaxProbeBytes,
    StableSuccess,
}

/// Mutable run-time state that tracks progress against the budget.
#[derive(Debug, Default)]
pub struct BudgetState {
    /// Current number of active arms.
    pub active_arms: u32,
    /// Elapsed time in milliseconds since the run began.
    pub elapsed_ms: u64,
    /// Cumulative bytes transferred across all probes.
    pub probe_bytes: u64,
    /// Set to `Some` after the first passing probe is recorded.
    /// The value is a confirmation counter: starts at 0; once a
    /// confirmation attempt is recorded it becomes 1, which satisfies
    /// the `confirm_once` contract and trips `StableSuccess`.
    pub first_success_confirmations: Option<u32>,
}

impl BudgetState {
    /// Check all caps against `budget`.
    ///
    /// Returns `Some(cap)` as soon as one is breached, or `None` when
    /// the run may continue.
    pub fn check(&self, budget: &AttemptBudget) -> Option<BudgetTrip> {
        if self.active_arms > budget.max_active_arms {
            return Some(BudgetTrip::MaxActiveArms);
        }
        if self.elapsed_ms > budget.max_elapsed_ms {
            return Some(BudgetTrip::MaxElapsedMs);
        }
        if self.probe_bytes > budget.max_probe_bytes {
            return Some(BudgetTrip::MaxProbeBytes);
        }
        if budget.stop_on_first_stable_success {
            if let Some(confirmations) = self.first_success_confirmations {
                if confirmations >= 1 {
                    return Some(BudgetTrip::StableSuccess);
                }
            }
        }
        None
    }

    /// Record that a probe succeeded for the first time.
    ///
    /// Subsequent calls are no-ops (the counter is only initialised once).
    pub fn record_first_success(&mut self) {
        if self.first_success_confirmations.is_none() {
            self.first_success_confirmations = Some(0);
        }
    }

    /// Record one confirmation attempt for the first success.
    ///
    /// Must be called after [`record_first_success`]; a call before that
    /// is a no-op so callers do not need to guard the order.
    pub fn record_confirmation(&mut self) {
        if let Some(ref mut count) = self.first_success_confirmations {
            *count = count.saturating_add(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn default_budget() -> AttemptBudget {
        AttemptBudget::default()
    }

    // ── cap: max_active_arms ────────────────────────────────────────────────

    #[test]
    fn check_trips_max_active_arms_when_exceeded() {
        let budget = default_budget();
        let state = BudgetState { active_arms: budget.max_active_arms + 1, ..Default::default() };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxActiveArms));
    }

    #[test]
    fn check_allows_active_arms_at_limit() {
        let budget = default_budget();
        let state = BudgetState { active_arms: budget.max_active_arms, ..Default::default() };
        assert_eq!(state.check(&budget), None);
    }

    // ── cap: max_elapsed_ms ─────────────────────────────────────────────────

    #[test]
    fn check_trips_max_elapsed_ms_when_exceeded() {
        let budget = default_budget();
        let state = BudgetState { elapsed_ms: budget.max_elapsed_ms + 1, ..Default::default() };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxElapsedMs));
    }

    #[test]
    fn check_allows_elapsed_ms_at_limit() {
        let budget = default_budget();
        let state = BudgetState { elapsed_ms: budget.max_elapsed_ms, ..Default::default() };
        assert_eq!(state.check(&budget), None);
    }

    // ── cap: max_probe_bytes ────────────────────────────────────────────────

    #[test]
    fn check_trips_max_probe_bytes_when_exceeded() {
        let budget = default_budget();
        let state = BudgetState { probe_bytes: budget.max_probe_bytes + 1, ..Default::default() };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxProbeBytes));
    }

    #[test]
    fn check_allows_probe_bytes_at_limit() {
        let budget = default_budget();
        let state = BudgetState { probe_bytes: budget.max_probe_bytes, ..Default::default() };
        assert_eq!(state.check(&budget), None);
    }

    // ── cap: stop_on_first_stable_success ───────────────────────────────────

    #[test]
    fn check_does_not_trip_stable_success_before_any_success_recorded() {
        let budget = default_budget();
        let state = BudgetState::default();
        assert_eq!(state.check(&budget), None);
    }

    #[test]
    fn check_does_not_trip_stable_success_after_first_success_but_before_confirmation() {
        let budget = default_budget();
        let mut state = BudgetState::default();
        state.record_first_success();
        // confirmations == 0 → not yet stable
        assert_eq!(state.check(&budget), None);
    }

    #[test]
    fn check_trips_stable_success_after_one_confirmation() {
        let budget = default_budget();
        let mut state = BudgetState::default();
        state.record_first_success();
        state.record_confirmation();
        assert_eq!(state.check(&budget), Some(BudgetTrip::StableSuccess));
    }

    #[test]
    fn check_does_not_trip_stable_success_when_flag_is_false() {
        let budget = AttemptBudget { stop_on_first_stable_success: false, ..default_budget() };
        let mut state = BudgetState::default();
        state.record_first_success();
        state.record_confirmation();
        // flag disabled → cap must not fire
        assert_eq!(state.check(&budget), None);
    }

    // ── confirm_once interaction ─────────────────────────────────────────────

    #[test]
    fn record_confirmation_before_first_success_is_noop() {
        let budget = default_budget();
        let mut state = BudgetState::default();
        state.record_confirmation(); // should be ignored
        assert_eq!(state.first_success_confirmations, None);
        assert_eq!(state.check(&budget), None);
    }

    #[test]
    fn second_record_first_success_is_noop() {
        let mut state = BudgetState::default();
        state.record_first_success();
        state.record_confirmation();
        let confirmations_after_first = state.first_success_confirmations;
        state.record_first_success(); // second call must be ignored
        assert_eq!(state.first_success_confirmations, confirmations_after_first);
    }

    // ── default constants match spec ────────────────────────────────────────

    #[test]
    fn default_budget_matches_spec() {
        let b = AttemptBudget::default();
        assert_eq!(b.max_active_arms, 5);
        assert_eq!(b.max_elapsed_ms, 6_000);
        assert_eq!(b.max_probe_bytes, 65_536);
        assert!(b.stop_on_first_stable_success);
    }

    // ── each cap fires independently ─────────────────────────────────────────

    #[test]
    fn only_arms_breached_reports_arms() {
        let budget = default_budget();
        let state = BudgetState {
            active_arms: budget.max_active_arms + 1,
            elapsed_ms: 0,
            probe_bytes: 0,
            first_success_confirmations: None,
        };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxActiveArms));
    }

    #[test]
    fn only_elapsed_breached_reports_elapsed() {
        let budget = default_budget();
        let state = BudgetState {
            active_arms: 0,
            elapsed_ms: budget.max_elapsed_ms + 1,
            probe_bytes: 0,
            first_success_confirmations: None,
        };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxElapsedMs));
    }

    #[test]
    fn only_bytes_breached_reports_bytes() {
        let budget = default_budget();
        let state = BudgetState {
            active_arms: 0,
            elapsed_ms: 0,
            probe_bytes: budget.max_probe_bytes + 1,
            first_success_confirmations: None,
        };
        assert_eq!(state.check(&budget), Some(BudgetTrip::MaxProbeBytes));
    }

    #[test]
    fn only_stable_success_breached_reports_stable_success() {
        let budget = default_budget();
        let mut state =
            BudgetState { active_arms: 0, elapsed_ms: 0, probe_bytes: 0, first_success_confirmations: None };
        state.record_first_success();
        state.record_confirmation();
        assert_eq!(state.check(&budget), Some(BudgetTrip::StableSuccess));
    }
}
