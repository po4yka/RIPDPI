//! Priority-based outbound failover state machine.
//!
//! # Why this lives in `ripdpi-runtime-strategy`
//!
//! The task `add-priority-based-outbound-failover-state-machine` pins its
//! Verify command to `cargo nextest run -p ripdpi-runtime-strategy` and
//! scopes edits to `ripdpi-runtime-strategy/**` and
//! `ripdpi-runtime-policy/**`. The state machine is a *runtime strategy*
//! decision -- "given the current network and health signals, which
//! outbound role should carry traffic right now" -- which is exactly the
//! responsibility of this crate (sibling to `strategy_evolver`, which
//! decides DPI-evasion parameter combos). It is self-contained: it pulls
//! nothing from `ripdpi-runtime-policy`, so the whole feature plus its
//! tests land where the contract verifies.
//!
//! # What it models
//!
//! A single device profile usually carries several outbound *roles*:
//!
//! * **Primary** -- a REALITY (VLESS+TLS) outbound. The preferred path:
//!   highest censorship resistance, lowest fingerprint.
//! * **HTTPS fallback** -- a plain HTTPS-tunnelled outbound. Used when the
//!   primary is blocked but TLS/443 still flows.
//! * **Speed (Hysteria2)** -- a UDP/QUIC outbound. Fast, but fragile: only
//!   a viable auto candidate once UDP/443 is *confirmed* reachable on the
//!   current network. A fast-but-fragile UDP path must never be chosen
//!   blind.
//!
//! Auto mode walks those roles in strict priority order. The five states
//! the machine can occupy:
//!
//! | State | Meaning |
//! |-------|---------|
//! | [`FailoverState::ConnectedPrimary`] | Primary REALITY is the active outbound. |
//! | [`FailoverState::TryHttpsFallback`] | Primary failed; HTTPS fallback is being tried. |
//! | [`FailoverState::TryHysteria2`] | Primary + HTTPS failed; Hysteria2 is being tried (UDP confirmed viable). |
//! | [`FailoverState::WhitelistModeHint`] | Every role failed but UDP was never viable -- hint the user toward whitelist mode. |
//! | [`FailoverState::BlockedReconnecting`] | Every role failed; backing off before a fresh cycle. |
//!
//! # Manual override
//!
//! A user can pin any role via [`OutboundFailover::set_manual_override`].
//! While an override is in effect, auto transitions are suspended and the
//! machine reports the pinned role's state. [`OutboundFailover::clear_manual_override`]
//! returns to auto, restarting the priority walk from the primary.
//!
//! # Non-interruption invariant
//!
//! Health signals (probe results, URL-test scores) never *by themselves*
//! tear down a working outbound. [`OutboundFailover::on_probe`] only
//! advances state when the *active* role is observed failing, or when a
//! higher-priority role recovers and an explicit
//! [`FailoverTrigger::EmergencyFailover`] / user action permits the swap.
//! A passive probe of a non-active role just updates that role's health
//! for future decisions.

use std::time::Duration;

/// The three outbound roles a single device profile can failover between,
/// in descending priority order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum OutboundRole {
    /// Primary REALITY (VLESS + TLS) outbound -- preferred.
    Primary,
    /// HTTPS-tunnelled fallback outbound.
    HttpsFallback,
    /// Hysteria2 (UDP/QUIC) speed outbound -- gated on UDP viability.
    Hysteria2,
}

impl OutboundRole {
    /// Priority rank, lower is preferred. Auto mode always tries lower
    /// ranks first.
    pub fn priority_rank(self) -> u8 {
        match self {
            Self::Primary => 0,
            Self::HttpsFallback => 1,
            Self::Hysteria2 => 2,
        }
    }

    /// The roles in strict auto-mode priority order.
    pub fn auto_order() -> [OutboundRole; 3] {
        [Self::Primary, Self::HttpsFallback, Self::Hysteria2]
    }

    /// `true` for roles whose transport is UDP-based and therefore gated on
    /// network UDP viability before they can be an auto candidate.
    pub fn requires_udp_viability(self) -> bool {
        matches!(self, Self::Hysteria2)
    }
}

/// The five connection states of the failover machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailoverState {
    /// Primary REALITY outbound is active and carrying traffic.
    ConnectedPrimary,
    /// Primary is down; the HTTPS fallback is the active candidate.
    TryHttpsFallback,
    /// Primary and HTTPS are down; Hysteria2 is the active candidate
    /// (only reachable when UDP/443 viability was confirmed).
    TryHysteria2,
    /// All roles failed and UDP was never viable, so Hysteria2 could not be
    /// tried -- surface a whitelist-mode hint to the user.
    WhitelistModeHint,
    /// All available roles failed; backing off before another failover
    /// cycle.
    BlockedReconnecting,
}

impl FailoverState {
    /// The active outbound role for this state, if any. `WhitelistModeHint`
    /// and `BlockedReconnecting` have no active role.
    pub fn active_role(self) -> Option<OutboundRole> {
        match self {
            Self::ConnectedPrimary => Some(OutboundRole::Primary),
            Self::TryHttpsFallback => Some(OutboundRole::HttpsFallback),
            Self::TryHysteria2 => Some(OutboundRole::Hysteria2),
            Self::WhitelistModeHint | Self::BlockedReconnecting => None,
        }
    }

    /// The state whose active role is `role`.
    fn for_role(role: OutboundRole) -> Self {
        match role {
            OutboundRole::Primary => Self::ConnectedPrimary,
            OutboundRole::HttpsFallback => Self::TryHttpsFallback,
            OutboundRole::Hysteria2 => Self::TryHysteria2,
        }
    }
}

/// Health of one outbound role, updated by probes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoleHealth {
    /// No probe has landed yet -- unknown.
    Unknown,
    /// The most recent probe(s) succeeded.
    Healthy,
    /// The most recent probe(s) failed.
    Failed,
}

/// A health-probe outcome for a specific role.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProbeOutcome {
    pub role: OutboundRole,
    /// `true` when the probe reached the outbound and a URL-test request
    /// succeeded.
    pub reachable: bool,
    /// URL-test latency sample, used for scoring. `None` when unreachable.
    pub latency: Option<Duration>,
}

impl ProbeOutcome {
    pub fn success(role: OutboundRole, latency: Duration) -> Self {
        Self { role, reachable: true, latency: Some(latency) }
    }

    pub fn failure(role: OutboundRole) -> Self {
        Self { role, reachable: false, latency: None }
    }
}

/// What kind of event is driving a transition. Distinguishes passive health
/// observation from explicit user / emergency actions so the
/// non-interruption invariant can be enforced.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailoverTrigger {
    /// A routine health probe landed. May only advance state when the
    /// *active* role is the one observed failing.
    HealthProbe,
    /// The user (or a fail-closed emergency policy) explicitly asked the
    /// machine to re-evaluate and swap to the best available role even if
    /// the current one still works. The probe outcome carried alongside the
    /// trigger is recorded for health tracking but otherwise ignored: the
    /// transition is driven by the trigger alone.
    EmergencyFailover,
}

/// Per-role runtime health and scoring state.
#[derive(Debug, Clone, Copy)]
struct RoleState {
    health: RoleHealth,
    /// Consecutive probe failures for this role. Drives the
    /// `failure_threshold` that trips a failover.
    consecutive_failures: u32,
    /// URL-test style score: an EWMA of recent latencies in milliseconds.
    /// Lower is better; `None` until the first successful probe.
    score_ms: Option<f64>,
}

impl RoleState {
    const fn new() -> Self {
        Self { health: RoleHealth::Unknown, consecutive_failures: 0, score_ms: None }
    }

    /// Fold a probe outcome into this role's health and URL-test score.
    fn observe(&mut self, outcome: ProbeOutcome, score_smoothing: f64) {
        if outcome.reachable {
            self.health = RoleHealth::Healthy;
            self.consecutive_failures = 0;
            if let Some(latency) = outcome.latency {
                let sample = latency.as_secs_f64() * 1000.0;
                self.score_ms = Some(match self.score_ms {
                    None => sample,
                    Some(prev) => prev * (1.0 - score_smoothing) + sample * score_smoothing,
                });
            }
        } else {
            self.health = RoleHealth::Failed;
            self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        }
    }
}

/// Tunable knobs for the failover machine.
#[derive(Debug, Clone, Copy)]
pub struct FailoverConfig {
    /// Consecutive probe failures on the *active* role before auto mode
    /// fails over to the next-priority role. Keeping this above 1 avoids
    /// flapping on a single transient probe miss.
    pub failure_threshold: u32,
    /// Backoff applied in [`FailoverState::BlockedReconnecting`] before the
    /// priority walk restarts from the primary.
    pub reconnect_backoff: Duration,
    /// EWMA smoothing factor for the URL-test score, in `(0, 1]`. Higher
    /// weights recent samples more.
    pub score_smoothing: f64,
    /// Minimum spacing between health probes of the *same* endpoint. Probes
    /// closer than this are rejected by [`OutboundFailover::probe_allowed`]
    /// so health checks never form a recognizable high-frequency pattern
    /// against one endpoint.
    pub min_probe_spacing: Duration,
}

impl Default for FailoverConfig {
    fn default() -> Self {
        Self {
            failure_threshold: 2,
            reconnect_backoff: Duration::from_secs(5),
            score_smoothing: 0.5,
            min_probe_spacing: Duration::from_secs(15),
        }
    }
}

impl FailoverConfig {
    /// Defensive normalization for caller-supplied knobs: `score_smoothing`
    /// is clamped into `(0, 1]` (NaN and non-positive values fall back to
    /// 1.0 so the EWMA always advances), and a zero `failure_threshold` is
    /// lifted to 1 so roles stay usable after a failure instead of being
    /// permanently excluded from every resolution walk.
    fn sanitized(mut self) -> Self {
        if !(self.score_smoothing.is_finite() && self.score_smoothing > 0.0 && self.score_smoothing <= 1.0) {
            self.score_smoothing = 1.0;
        }
        self.failure_threshold = self.failure_threshold.max(1);
        self
    }
}

/// UDP/443 viability for the current network, gating Hysteria2.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpViability {
    /// Not yet measured for this network.
    Unknown,
    /// UDP/443 confirmed reachable -- Hysteria2 may be an auto candidate.
    Viable,
    /// UDP/443 confirmed blocked -- Hysteria2 is excluded from auto mode.
    Blocked,
}

/// Non-mutating answer to a request to leave the TLS transport family after
/// a behavioral post-handshake failure. The caller decides whether and when
/// to apply the recommendation; evaluating it never interrupts traffic.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportPivotDecision {
    Use(OutboundRole),
    NoHealthyCandidate,
    ManualOverride(OutboundRole),
}

impl UdpViability {
    /// `true` only when UDP has been *confirmed* viable. `Unknown` is not
    /// good enough: a fast-but-fragile UDP path is never chosen blind.
    fn allows_hysteria2(self) -> bool {
        matches!(self, Self::Viable)
    }
}

/// The priority-based outbound failover state machine for one device
/// profile.
#[derive(Debug, Clone)]
pub struct OutboundFailover {
    config: FailoverConfig,
    state: FailoverState,
    /// Per-role health/score, indexed by [`OutboundRole::priority_rank`].
    roles: [RoleState; 3],
    /// UDP/443 viability for the current network.
    udp_viability: UdpViability,
    /// When `Some`, the user has pinned this role and auto transitions are
    /// suspended.
    manual_override: Option<OutboundRole>,
    /// Monotonic "now" in milliseconds, advanced by the caller via
    /// [`OutboundFailover::advance_clock`]. Kept caller-driven so the
    /// machine has no implicit timer thread and stays trivially testable.
    now_ms: u64,
    /// `now_ms` of the last probe accepted for each role, for probe-spacing
    /// enforcement.
    last_probe_ms: [Option<u64>; 3],
    /// `now_ms` at which the current `BlockedReconnecting` backoff expires.
    backoff_until_ms: Option<u64>,
}

impl OutboundFailover {
    /// Create a failover machine starting on the primary REALITY outbound.
    pub fn new(config: FailoverConfig) -> Self {
        Self {
            config: config.sanitized(),
            state: FailoverState::ConnectedPrimary,
            roles: [RoleState::new(); 3],
            udp_viability: UdpViability::Unknown,
            manual_override: None,
            now_ms: 0,
            last_probe_ms: [None; 3],
            backoff_until_ms: None,
        }
    }

    /// Create a failover machine with default tuning.
    pub fn with_defaults() -> Self {
        Self::new(FailoverConfig::default())
    }

    /// The current connection state.
    pub fn state(&self) -> FailoverState {
        self.state
    }

    /// The outbound role traffic should currently use. `None` while blocked
    /// or hinting whitelist mode.
    pub fn active_role(&self) -> Option<OutboundRole> {
        match self.manual_override {
            Some(role) => Some(role),
            None => self.state.active_role(),
        }
    }

    /// `true` when a user-pinned manual override is in effect.
    pub fn is_manual(&self) -> bool {
        self.manual_override.is_some()
    }

    /// Recommend a configured UDP/QUIC role only when both network viability
    /// and the role's own health have been confirmed. Manual overrides remain
    /// authoritative, and this method never changes the active state.
    pub fn recommend_udp_quic_pivot(&self) -> TransportPivotDecision {
        if let Some(role) = self.manual_override {
            return TransportPivotDecision::ManualOverride(role);
        }
        let hysteria = &self.roles[OutboundRole::Hysteria2.priority_rank() as usize];
        if self.udp_viability.allows_hysteria2() && hysteria.health == RoleHealth::Healthy {
            TransportPivotDecision::Use(OutboundRole::Hysteria2)
        } else {
            TransportPivotDecision::NoHealthyCandidate
        }
    }

    /// The user-pinned role, if any.
    pub fn manual_override(&self) -> Option<OutboundRole> {
        self.manual_override
    }

    /// Health of a specific role as last observed by a probe.
    pub fn role_health(&self, role: OutboundRole) -> RoleHealth {
        self.roles[role.priority_rank() as usize].health
    }

    /// URL-test style score for a role (EWMA of latency in ms, lower is
    /// better). `None` until the role has had a successful probe.
    pub fn role_score_ms(&self, role: OutboundRole) -> Option<f64> {
        self.roles[role.priority_rank() as usize].score_ms
    }

    /// Current UDP/443 viability for the network.
    pub fn udp_viability(&self) -> UdpViability {
        self.udp_viability
    }

    /// Advance the caller-driven monotonic clock by `delta`. Used to expire
    /// the reconnect backoff and to space out probes.
    pub fn advance_clock(&mut self, delta: Duration) {
        self.now_ms = self.now_ms.saturating_add(delta.as_millis() as u64);
    }

    /// Pin the active outbound to `role`, suspending auto transitions. The
    /// user can always see and reset this (see [`Self::clear_manual_override`]).
    /// Pinning does not by itself interrupt an already-working outbound:
    /// the *next* connection uses the pinned role; in-flight traffic is the
    /// caller's concern.
    pub fn set_manual_override(&mut self, role: OutboundRole) {
        self.manual_override = Some(role);
    }

    /// Clear a manual override and return to auto mode, restarting the
    /// priority walk from the primary REALITY outbound.
    pub fn clear_manual_override(&mut self) {
        self.manual_override = None;
        self.state = FailoverState::ConnectedPrimary;
        self.backoff_until_ms = None;
    }

    /// Record the UDP/443 viability measurement for the current network.
    /// When UDP becomes [`UdpViability::Blocked`] and Hysteria2 is the
    /// active auto role, the machine immediately re-evaluates so it does
    /// not keep traffic on a path the network no longer permits.
    ///
    /// Asymmetry by design: becoming [`UdpViability::Viable`] does *not*
    /// proactively exit [`FailoverState::WhitelistModeHint`]; the machine
    /// waits for the next accepted probe (or reconnect poll) to re-walk,
    /// keeping all transitions probe-driven.
    pub fn set_udp_viability(&mut self, viability: UdpViability) {
        self.udp_viability = viability;
        if self.manual_override.is_some() {
            return;
        }
        if !viability.allows_hysteria2() && self.state == FailoverState::TryHysteria2 {
            // The active UDP path is no longer permitted -- this is an
            // environment change, not a passive health blip, so re-walk.
            self.state = self.resolve_blocked_terminal();
        }
    }

    /// `true` when a health probe of `role` is allowed to run *now* given
    /// the minimum probe-spacing knob. Callers should gate their probe
    /// scheduling on this so health checks never form a recognizable,
    /// high-frequency pattern against a single endpoint.
    pub fn probe_allowed(&self, role: OutboundRole) -> bool {
        match self.last_probe_ms[role.priority_rank() as usize] {
            None => true,
            Some(last) => self.now_ms.saturating_sub(last) >= self.config.min_probe_spacing.as_millis() as u64,
        }
    }

    /// Feed a health-probe outcome into the machine.
    ///
    /// `trigger` distinguishes a routine [`FailoverTrigger::HealthProbe`]
    /// (which may only advance state when the *active* role is failing,
    /// preserving the non-interruption invariant) from an
    /// [`FailoverTrigger::EmergencyFailover`] (user / fail-closed policy
    /// explicitly asking for the best available role right now).
    ///
    /// Returns the state after applying the outcome.
    pub fn on_probe(&mut self, outcome: ProbeOutcome, trigger: FailoverTrigger) -> FailoverState {
        let rank = outcome.role.priority_rank() as usize;
        self.last_probe_ms[rank] = Some(self.now_ms);
        self.roles[rank].observe(outcome, self.config.score_smoothing);

        if self.manual_override.is_some() {
            // Auto transitions are suspended; health is still recorded
            // above for when the user returns to auto.
            return self.state;
        }

        match trigger {
            FailoverTrigger::HealthProbe => self.apply_health_probe(outcome.role),
            FailoverTrigger::EmergencyFailover => self.apply_emergency_failover(),
        }
    }

    /// Routine-probe transition logic. Only the *active* role failing can
    /// move the machine forward; a probe of any other role just updates
    /// health for future decisions and leaves state untouched.
    fn apply_health_probe(&mut self, probed: OutboundRole) -> FailoverState {
        if self.state == FailoverState::BlockedReconnecting {
            // While backing off, a probe landing after the backoff window
            // is the trigger to give every role a fresh chance and restart
            // the priority walk.
            if self.backoff_expired() {
                self.state = self.restart_after_backoff();
            }
            return self.state;
        }

        let Some(active) = self.state.active_role() else {
            // WhitelistModeHint: any accepted probe -- including a failing
            // one -- is a chance to recover; re-walk the whole priority
            // order in case UDP became viable or a higher-priority role
            // came back.
            self.state = self.resolve_from(OutboundRole::Primary);
            return self.state;
        };

        if probed != active {
            // Non-active role probed: record-only, never interrupt the
            // working outbound.
            return self.state;
        }

        if self.roles[active.priority_rank() as usize].consecutive_failures >= self.config.failure_threshold {
            // The active role has crossed the failure threshold -- fail
            // over to the next priority role.
            self.state = self.resolve_from_next(active);
        }
        self.state
    }

    /// Emergency-failover transition logic: re-walk the whole priority
    /// order and land on the best currently-usable role, regardless of
    /// whether the current one still works. This is the only path that may
    /// move *off* a still-healthy active role, and only because the caller
    /// explicitly asked for it.
    fn apply_emergency_failover(&mut self) -> FailoverState {
        self.state = self.resolve_from(OutboundRole::Primary);
        self.state
    }

    /// Walk the priority order starting *after* `from` and return the state
    /// for the first usable role, or a terminal state if none remain.
    fn resolve_from_next(&mut self, from: OutboundRole) -> FailoverState {
        let start_rank = from.priority_rank() + 1;
        for role in OutboundRole::auto_order() {
            if role.priority_rank() < start_rank {
                continue;
            }
            if self.role_is_usable(role) {
                return FailoverState::for_role(role);
            }
        }
        self.resolve_blocked_terminal()
    }

    /// Walk the priority order starting *at* `from` and return the state
    /// for the first usable role, or a terminal state if none remain.
    fn resolve_from(&mut self, from: OutboundRole) -> FailoverState {
        for role in OutboundRole::auto_order() {
            if role.priority_rank() < from.priority_rank() {
                continue;
            }
            if self.role_is_usable(role) {
                return FailoverState::for_role(role);
            }
        }
        self.resolve_blocked_terminal()
    }

    /// `true` when `role` may currently be selected by auto mode: it must
    /// not be in a `Failed` state past the threshold, and a UDP-based role
    /// additionally requires confirmed UDP viability.
    fn role_is_usable(&self, role: OutboundRole) -> bool {
        if role.requires_udp_viability() && !self.udp_viability.allows_hysteria2() {
            return false;
        }
        let state = &self.roles[role.priority_rank() as usize];
        // A role is usable unless its consecutive failures have crossed the
        // threshold. `Unknown` and `Healthy` are both usable -- auto mode
        // optimistically tries an unprobed role rather than skipping it.
        state.consecutive_failures < self.config.failure_threshold
    }

    /// Decide the terminal state when no role is usable.
    ///
    /// A [`FailoverState::WhitelistModeHint`] is reported only when
    /// Hysteria2 was held back *purely* because UDP viability was never
    /// measured ([`UdpViability::Unknown`]) and Hysteria2 itself has not
    /// failed -- in that situation the user has an actionable alternative
    /// (whitelist mode may reveal a usable path). When UDP is *confirmed*
    /// [`UdpViability::Blocked`], or Hysteria2 itself failed, every role is
    /// genuinely exhausted, so the machine enters
    /// [`FailoverState::BlockedReconnecting`] and backs off.
    fn resolve_blocked_terminal(&mut self) -> FailoverState {
        let hysteria2_failures = self.roles[OutboundRole::Hysteria2.priority_rank() as usize].consecutive_failures;
        let hysteria2_held_back_by_unmeasured_udp =
            self.udp_viability == UdpViability::Unknown && hysteria2_failures < self.config.failure_threshold;
        if hysteria2_held_back_by_unmeasured_udp {
            FailoverState::WhitelistModeHint
        } else {
            self.backoff_until_ms = Some(self.now_ms + self.config.reconnect_backoff.as_millis() as u64);
            FailoverState::BlockedReconnecting
        }
    }

    /// `true` once the reconnect backoff window has elapsed.
    fn backoff_expired(&self) -> bool {
        match self.backoff_until_ms {
            None => true,
            Some(until) => self.now_ms >= until,
        }
    }

    /// Restart the priority walk from the primary after a reconnect
    /// backoff. The backoff is the machine's "give everything a fresh
    /// chance" point: per-role failure counters are cleared so the walk
    /// can actually re-select a role that was previously exhausted.
    /// URL-test scores are kept -- they are long-lived quality signals,
    /// not failure state. Health is reset to `Unknown` to mark the role
    /// un-probed for this new cycle.
    fn restart_after_backoff(&mut self) -> FailoverState {
        for role_state in &mut self.roles {
            role_state.consecutive_failures = 0;
            role_state.health = RoleHealth::Unknown;
        }
        self.backoff_until_ms = None;
        self.resolve_from(OutboundRole::Primary)
    }

    /// Drive a `BlockedReconnecting` machine: once the backoff elapses,
    /// restart the priority walk from the primary. A no-op in every other
    /// state. Returns the (possibly unchanged) state.
    pub fn poll_reconnect(&mut self) -> FailoverState {
        if self.manual_override.is_some() {
            return self.state;
        }
        if self.state == FailoverState::BlockedReconnecting && self.backoff_expired() {
            self.state = self.restart_after_backoff();
        }
        self.state
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn healthy(role: OutboundRole) -> ProbeOutcome {
        ProbeOutcome::success(role, Duration::from_millis(40))
    }

    fn failed(role: OutboundRole) -> ProbeOutcome {
        ProbeOutcome::failure(role)
    }

    /// Push `n` consecutive failed health probes for `role` into `fo`.
    fn fail_n(fo: &mut OutboundFailover, role: OutboundRole, n: u32) {
        for _ in 0..n {
            fo.on_probe(failed(role), FailoverTrigger::HealthProbe);
        }
    }

    // --- role / state plumbing ---------------------------------------------

    #[test]
    fn role_priority_order_is_primary_then_https_then_hysteria2() {
        assert!(OutboundRole::Primary.priority_rank() < OutboundRole::HttpsFallback.priority_rank());
        assert!(OutboundRole::HttpsFallback.priority_rank() < OutboundRole::Hysteria2.priority_rank());
        assert_eq!(
            OutboundRole::auto_order(),
            [OutboundRole::Primary, OutboundRole::HttpsFallback, OutboundRole::Hysteria2],
        );
    }

    #[test]
    fn only_hysteria2_requires_udp_viability() {
        assert!(OutboundRole::Hysteria2.requires_udp_viability());
        assert!(!OutboundRole::Primary.requires_udp_viability());
        assert!(!OutboundRole::HttpsFallback.requires_udp_viability());
    }

    #[test]
    fn all_five_states_are_representable_and_map_to_roles() {
        assert_eq!(FailoverState::ConnectedPrimary.active_role(), Some(OutboundRole::Primary));
        assert_eq!(FailoverState::TryHttpsFallback.active_role(), Some(OutboundRole::HttpsFallback));
        assert_eq!(FailoverState::TryHysteria2.active_role(), Some(OutboundRole::Hysteria2));
        assert_eq!(FailoverState::WhitelistModeHint.active_role(), None);
        assert_eq!(FailoverState::BlockedReconnecting.active_role(), None);
    }

    #[test]
    fn fresh_machine_starts_connected_on_primary() {
        let fo = OutboundFailover::with_defaults();
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
        assert_eq!(fo.active_role(), Some(OutboundRole::Primary));
        assert!(!fo.is_manual());
    }

    // --- priority walk: primary -> https -> hysteria2 ----------------------

    #[test]
    fn auto_mode_tries_https_fallback_before_hysteria2() {
        let mut fo = OutboundFailover::with_defaults();
        // UDP is viable, so Hysteria2 *could* be picked -- but HTTPS must
        // still come first.
        fo.set_udp_viability(UdpViability::Viable);
        fail_n(&mut fo, OutboundRole::Primary, 2);
        assert_eq!(fo.state(), FailoverState::TryHttpsFallback);
        assert_eq!(fo.active_role(), Some(OutboundRole::HttpsFallback));
    }

    #[test]
    fn auto_mode_reaches_hysteria2_only_after_primary_and_https_fail() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Viable);
        fail_n(&mut fo, OutboundRole::Primary, 2);
        assert_eq!(fo.state(), FailoverState::TryHttpsFallback);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        assert_eq!(fo.state(), FailoverState::TryHysteria2);
        assert_eq!(fo.active_role(), Some(OutboundRole::Hysteria2));
    }

    // --- UDP viability gate -------------------------------------------------

    #[test]
    fn hysteria2_is_not_an_auto_candidate_until_udp_is_confirmed_viable() {
        let mut fo = OutboundFailover::with_defaults();
        // UDP viability is Unknown (never measured). Primary + HTTPS fail.
        fail_n(&mut fo, OutboundRole::Primary, 2);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        // Hysteria2 must NOT be chosen -- instead, hint whitelist mode.
        assert_ne!(fo.state(), FailoverState::TryHysteria2);
        assert_eq!(fo.state(), FailoverState::WhitelistModeHint);
    }

    #[test]
    fn hysteria2_excluded_when_udp_confirmed_blocked() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Blocked);
        fail_n(&mut fo, OutboundRole::Primary, 2);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        // UDP confirmed blocked: Hysteria2 is genuinely unavailable, so this
        // is a hard block, not a whitelist-mode hint.
        assert_eq!(fo.state(), FailoverState::BlockedReconnecting);
    }

    #[test]
    fn udp_becoming_blocked_re_evaluates_active_hysteria2() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Viable);
        fail_n(&mut fo, OutboundRole::Primary, 2);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        assert_eq!(fo.state(), FailoverState::TryHysteria2);
        // The network changes: UDP is now blocked. The machine must not keep
        // traffic on a path the network no longer permits.
        fo.set_udp_viability(UdpViability::Blocked);
        assert_ne!(fo.state(), FailoverState::TryHysteria2);
        assert_eq!(fo.state(), FailoverState::BlockedReconnecting);
    }

    // --- non-interruption invariant ----------------------------------------

    #[test]
    fn healthy_primary_is_not_interrupted_by_probe_of_other_roles() {
        let mut fo = OutboundFailover::with_defaults();
        // Probes of non-active roles -- even failing ones -- must not move
        // the machine off a working primary.
        fail_n(&mut fo, OutboundRole::HttpsFallback, 5);
        fail_n(&mut fo, OutboundRole::Hysteria2, 5);
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
        assert_eq!(fo.active_role(), Some(OutboundRole::Primary));
    }

    #[test]
    fn single_transient_failure_does_not_flap_below_threshold() {
        let mut fo = OutboundFailover::with_defaults(); // failure_threshold = 2
        fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        // One miss is below the threshold -- stay put.
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
        // A success resets the counter.
        fo.on_probe(healthy(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
    }

    #[test]
    fn config_sanitizes_degenerate_knobs() {
        // M3 regression: score_smoothing outside (0, 1] froze or poisoned the
        // EWMA, and a zero failure_threshold made every role permanently
        // unusable.
        let config =
            FailoverConfig { failure_threshold: 0, score_smoothing: f64::NAN, ..FailoverConfig::default() }.sanitized();
        assert_eq!(config.failure_threshold, 1);
        assert_eq!(config.score_smoothing, 1.0);

        let config = FailoverConfig { score_smoothing: 2.0, ..FailoverConfig::default() }.sanitized();
        assert_eq!(config.score_smoothing, 1.0);

        let config = FailoverConfig { score_smoothing: 0.25, ..FailoverConfig::default() }.sanitized();
        assert_eq!(config.score_smoothing, 0.25);
    }

    #[test]
    fn zero_failure_threshold_still_fails_over_after_one_miss() {
        let mut fo = OutboundFailover::new(FailoverConfig { failure_threshold: 0, ..FailoverConfig::default() });
        let state = fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        assert_eq!(state, FailoverState::TryHttpsFallback);
    }

    #[test]
    fn emergency_failover_may_move_off_a_still_healthy_role() {
        let mut fo = OutboundFailover::with_defaults();
        // Primary is healthy, but the user/emergency policy forces a re-walk.
        fo.on_probe(healthy(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        // Mark primary failed past threshold, then issue an emergency
        // failover via a probe carrying that trigger.
        fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        let state = fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::EmergencyFailover);
        assert_eq!(state, FailoverState::TryHttpsFallback);
    }

    // --- manual override ----------------------------------------------------

    #[test]
    fn manual_override_pins_role_and_suspends_auto_transitions() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_manual_override(OutboundRole::Hysteria2);
        assert!(fo.is_manual());
        assert_eq!(fo.manual_override(), Some(OutboundRole::Hysteria2));
        assert_eq!(fo.active_role(), Some(OutboundRole::Hysteria2));
        // Auto transitions are suspended: failing the pinned role does not
        // move the machine.
        fail_n(&mut fo, OutboundRole::Hysteria2, 10);
        assert_eq!(fo.active_role(), Some(OutboundRole::Hysteria2));
    }

    #[test]
    fn manual_override_can_be_reset_to_auto() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_manual_override(OutboundRole::HttpsFallback);
        assert!(fo.is_manual());
        fo.clear_manual_override();
        assert!(!fo.is_manual());
        // Reset restarts the priority walk from the primary.
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
        assert_eq!(fo.active_role(), Some(OutboundRole::Primary));
    }

    #[test]
    fn probe_health_is_still_recorded_under_manual_override() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_manual_override(OutboundRole::Primary);
        fo.on_probe(healthy(OutboundRole::HttpsFallback), FailoverTrigger::HealthProbe);
        // The override suspends *transitions*, not health bookkeeping, so a
        // later clear_manual_override sees the recorded health.
        assert_eq!(fo.role_health(OutboundRole::HttpsFallback), RoleHealth::Healthy);
    }

    #[test]
    fn udp_quic_pivot_requires_viability_and_healthy_hysteria() {
        let mut fo = OutboundFailover::with_defaults();
        assert_eq!(fo.recommend_udp_quic_pivot(), TransportPivotDecision::NoHealthyCandidate);
        fo.set_udp_viability(UdpViability::Viable);
        assert_eq!(fo.recommend_udp_quic_pivot(), TransportPivotDecision::NoHealthyCandidate);
        fo.on_probe(healthy(OutboundRole::Hysteria2), FailoverTrigger::HealthProbe);
        assert_eq!(fo.recommend_udp_quic_pivot(), TransportPivotDecision::Use(OutboundRole::Hysteria2));
    }

    #[test]
    fn udp_quic_pivot_respects_manual_override() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Viable);
        fo.on_probe(healthy(OutboundRole::Hysteria2), FailoverTrigger::HealthProbe);
        fo.set_manual_override(OutboundRole::Primary);
        assert_eq!(fo.recommend_udp_quic_pivot(), TransportPivotDecision::ManualOverride(OutboundRole::Primary));
    }

    // --- URL-test scoring ---------------------------------------------------

    #[test]
    fn url_test_score_is_an_ewma_of_latency_samples() {
        let mut fo = OutboundFailover::with_defaults(); // score_smoothing = 0.5
        assert_eq!(fo.role_score_ms(OutboundRole::Primary), None);
        fo.on_probe(
            ProbeOutcome::success(OutboundRole::Primary, Duration::from_millis(100)),
            FailoverTrigger::HealthProbe,
        );
        assert_eq!(fo.role_score_ms(OutboundRole::Primary), Some(100.0));
        fo.on_probe(
            ProbeOutcome::success(OutboundRole::Primary, Duration::from_millis(200)),
            FailoverTrigger::HealthProbe,
        );
        // EWMA: 100 * 0.5 + 200 * 0.5 = 150.
        assert_eq!(fo.role_score_ms(OutboundRole::Primary), Some(150.0));
    }

    #[test]
    fn failed_probe_does_not_disturb_the_score() {
        let mut fo = OutboundFailover::with_defaults();
        fo.on_probe(
            ProbeOutcome::success(OutboundRole::Primary, Duration::from_millis(80)),
            FailoverTrigger::HealthProbe,
        );
        fo.on_probe(failed(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        // The score reflects only successful URL-test samples.
        assert_eq!(fo.role_score_ms(OutboundRole::Primary), Some(80.0));
        assert_eq!(fo.role_health(OutboundRole::Primary), RoleHealth::Failed);
    }

    // --- probe spacing (anti-fingerprint) ----------------------------------

    #[test]
    fn probe_spacing_rejects_back_to_back_probes_of_the_same_endpoint() {
        let mut fo = OutboundFailover::with_defaults(); // min_probe_spacing = 15s
        assert!(fo.probe_allowed(OutboundRole::Primary));
        fo.on_probe(healthy(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        // Immediately after a probe, another is not allowed -- this is what
        // keeps health checks from forming a high-frequency pattern.
        assert!(!fo.probe_allowed(OutboundRole::Primary));
        // A different role is unaffected.
        assert!(fo.probe_allowed(OutboundRole::HttpsFallback));
        // After the spacing window, probing is allowed again.
        fo.advance_clock(Duration::from_secs(15));
        assert!(fo.probe_allowed(OutboundRole::Primary));
    }

    // --- blocked / reconnect backoff ---------------------------------------

    #[test]
    fn blocked_reconnecting_restarts_priority_walk_after_backoff() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Blocked);
        fail_n(&mut fo, OutboundRole::Primary, 2);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        assert_eq!(fo.state(), FailoverState::BlockedReconnecting);
        // Backoff not yet elapsed: still blocked.
        assert_eq!(fo.poll_reconnect(), FailoverState::BlockedReconnecting);
        // After the backoff window, the priority walk restarts from primary.
        fo.advance_clock(Duration::from_secs(5));
        assert_eq!(fo.poll_reconnect(), FailoverState::ConnectedPrimary);
    }

    #[test]
    fn recovery_from_whitelist_hint_when_udp_becomes_viable() {
        let mut fo = OutboundFailover::with_defaults();
        // Primary + HTTPS fail, UDP unknown -> whitelist hint.
        fail_n(&mut fo, OutboundRole::Primary, 2);
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        assert_eq!(fo.state(), FailoverState::WhitelistModeHint);
        // UDP becomes viable: a subsequent probe re-resolves toward Hysteria2.
        fo.set_udp_viability(UdpViability::Viable);
        let state = fo.on_probe(healthy(OutboundRole::Hysteria2), FailoverTrigger::HealthProbe);
        assert_eq!(state, FailoverState::TryHysteria2);
    }

    #[test]
    fn full_failover_chain_primary_to_blocked_then_recover() {
        let mut fo = OutboundFailover::with_defaults();
        fo.set_udp_viability(UdpViability::Viable);
        // Primary down.
        fail_n(&mut fo, OutboundRole::Primary, 2);
        assert_eq!(fo.state(), FailoverState::TryHttpsFallback);
        // HTTPS down.
        fail_n(&mut fo, OutboundRole::HttpsFallback, 2);
        assert_eq!(fo.state(), FailoverState::TryHysteria2);
        // Hysteria2 down -> everything failed, hard block.
        fail_n(&mut fo, OutboundRole::Hysteria2, 2);
        assert_eq!(fo.state(), FailoverState::BlockedReconnecting);
        // Backoff elapses, primary recovers in the meantime.
        fo.advance_clock(Duration::from_secs(5));
        fo.on_probe(healthy(OutboundRole::Primary), FailoverTrigger::HealthProbe);
        assert_eq!(fo.state(), FailoverState::ConnectedPrimary);
    }
}
