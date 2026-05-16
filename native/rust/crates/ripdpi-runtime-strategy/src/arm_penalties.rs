//! Rarity and repeated-attempt penalties for arm ranking.
//!
//! These penalty terms complement the Bayesian posterior score in
//! [`crate::scoring`].  They are computed from local, runtime-observed data
//! rather than static presets:
//!
//! * [`rarity_penalty`] — computed from the locally observed arm-selection
//!   frequency table.  Rare arms (infrequently selected) receive a higher
//!   penalty.  The frequency table is scoped to the current network-profile
//!   observation window and should be cleared by the caller whenever the
//!   active [`NetProfile`] changes.
//!
//! * [`AttemptMemory`] / [`repeated_attempt_penalty`] — tracks consecutive
//!   failures **per `(host eTLD+1, NetProfile)`**.  The penalty grows
//!   monotonically until it saturates at [`MAX_REPEATED_PENALTY`] after
//!   [`REPEATED_FAILURE_CAP`] consecutive failures.  Each entry is
//!   independent: hammering `hostA` does not affect the penalty for `hostB`.
//!
//! # Integration
//!
//! ```ignore
//! use ripdpi_runtime_strategy::arm_penalties::{
//!     AttemptMemory, apply_penalties, rarity_penalty, repeated_attempt_penalty,
//! };
//!
//! let mut memory = AttemptMemory::default();
//! let base_score = 0.75;
//! let freq = arm_freq_table(); // HashMap<arm_id, u32> maintained by caller
//! let r_pen  = rarity_penalty(&freq, "split");
//! let rep_pen = repeated_attempt_penalty(memory.consecutive_failures("host.com", &net));
//! let final_score = apply_penalties(base_score, r_pen, rep_pen);
//! ```

use std::collections::HashMap;

use crate::profiles::NetProfile;

// ── Constants ─────────────────────────────────────────────────────────────────

/// Number of consecutive failures after which the repeated-attempt penalty
/// saturates at [`MAX_REPEATED_PENALTY`].
pub const REPEATED_FAILURE_CAP: u32 = 5;

/// Maximum value returned by [`repeated_attempt_penalty`] (fully saturated).
pub const MAX_REPEATED_PENALTY: f64 = 1.0;

// ── Rarity penalty ────────────────────────────────────────────────────────────

/// Compute the rarity penalty for `arm_id` from the locally observed arm
/// selection-frequency table.
///
/// `frequencies` maps arm identifiers to their observed selection count within
/// the current network-profile observation window.  The caller is responsible
/// for resetting this map when the active [`NetProfile`] changes.
///
/// # Returns
///
/// A value in `[0.0, 1.0]`:
/// * `1.0` — the arm has never been selected **or** is the rarest arm in the
///   current window.
/// * `0.0` — the arm is the most-selected arm.
/// * Values in between scale linearly with relative frequency.
///
/// When the table is empty or all arms have zero counts, every arm returns
/// `1.0` (maximum penalty — no observations yet).
pub fn rarity_penalty(frequencies: &HashMap<String, u32>, arm_id: &str) -> f64 {
    let arm_count = frequencies.get(arm_id).copied().unwrap_or(0);
    let max_count = frequencies.values().copied().max().unwrap_or(0);

    if max_count == 0 {
        // No observations at all: every arm is equally rare.
        return 1.0;
    }

    // Penalty is inversely proportional to relative frequency.
    // arm_count / max_count gives [0, 1]; complement gives [0, 1] where 1
    // means "never seen" and 0 means "most frequent".
    1.0 - (arm_count as f64 / max_count as f64)
}

// ── Repeated-attempt memory ───────────────────────────────────────────────────

/// Key into [`AttemptMemory`]: a `(host eTLD+1, NetProfile)` pair.
///
/// The `NetProfile` is cloned into the key so that penalty entries are
/// automatically segregated by network profile.  When the network profile
/// changes, existing entries under the old profile are simply ignored by
/// callers (they remain in the map but are never looked up for the new profile
/// unless the profile cycles back).  Callers that want strict reset semantics
/// can call [`AttemptMemory::clear`].
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct AttemptKey {
    /// eTLD+1 of the target host (e.g. `"example.com"`).
    pub host: String,
    /// Serialised representation of the network profile used as hash key.
    /// We derive `Eq + Hash` from the fields we care about.
    pub net_profile_key: NetProfileKey,
}

/// A hashable, equality-comparable projection of [`NetProfile`] used as part
/// of [`AttemptKey`].
///
/// Only the fields that materially affect which arms succeed are included.
/// Fine-grained fields like exact ASN are intentionally omitted to keep
/// entries stable across minor network fluctuations.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct NetProfileKey {
    pub asn: u32,
    pub access_type_discriminant: u8,
}

impl NetProfileKey {
    /// Construct from a [`NetProfile`] reference.
    pub fn from_profile(net: &NetProfile) -> Self {
        use crate::profiles::AccessType;
        let disc = match net.access_type {
            AccessType::Unknown => 0,
            AccessType::Wifi => 1,
            AccessType::CellularNr => 2,
            AccessType::CellularLte => 3,
            AccessType::CellularOther => 4,
            AccessType::Ethernet => 5,
            AccessType::Vpn => 6,
            // Non-exhaustive: treat unknown future variants as generic.
            #[allow(unreachable_patterns)]
            _ => 255,
        };
        Self { asn: net.asn, access_type_discriminant: disc }
    }
}

/// Per-`(host, NetProfile)` attempt state stored by [`AttemptMemory`].
#[derive(Debug, Clone, Default)]
pub struct HostAttemptState {
    /// Number of consecutive failures since the last success for this key.
    pub consecutive_failures: u32,
}

/// Tracks consecutive failure counts keyed by `(host eTLD+1, NetProfile)`.
///
/// This is the runtime memory that feeds [`repeated_attempt_penalty`].
/// Entries for different hosts or different network profiles are fully
/// independent.
#[derive(Debug, Default)]
pub struct AttemptMemory {
    inner: HashMap<AttemptKey, HostAttemptState>,
}

impl AttemptMemory {
    /// Create an empty memory store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Return the current consecutive-failure count for `(host, net)`.
    /// Returns `0` for entries that have never been recorded.
    pub fn consecutive_failures(&self, host: &str, net: &NetProfile) -> u32 {
        let key = AttemptKey { host: host.to_owned(), net_profile_key: NetProfileKey::from_profile(net) };
        self.inner.get(&key).map_or(0, |s| s.consecutive_failures)
    }

    /// Record a successful attempt for `(host, net)`.
    /// Resets the consecutive-failure counter to zero.
    pub fn record_success(&mut self, host: &str, net: &NetProfile) {
        let key = AttemptKey { host: host.to_owned(), net_profile_key: NetProfileKey::from_profile(net) };
        let state = self.inner.entry(key).or_default();
        state.consecutive_failures = 0;
    }

    /// Record a failed attempt for `(host, net)`.
    /// Increments the consecutive-failure counter (saturating at `u32::MAX`).
    pub fn record_failure(&mut self, host: &str, net: &NetProfile) {
        let key = AttemptKey { host: host.to_owned(), net_profile_key: NetProfileKey::from_profile(net) };
        let state = self.inner.entry(key).or_default();
        state.consecutive_failures = state.consecutive_failures.saturating_add(1);
    }

    /// Remove all entries.  Call this when the network profile changes and you
    /// want strict reset semantics for all per-host counters.
    pub fn clear(&mut self) {
        self.inner.clear();
    }

    /// Remove all entries whose [`NetProfileKey`] matches `net`.
    /// Useful when only the current profile's state should be reset.
    pub fn clear_for_profile(&mut self, net: &NetProfile) {
        let key = NetProfileKey::from_profile(net);
        self.inner.retain(|k, _| k.net_profile_key != key);
    }
}

// ── Repeated-attempt penalty ──────────────────────────────────────────────────

/// Compute the repeated-attempt penalty from a consecutive-failure count.
///
/// The penalty grows linearly from `0.0` (zero failures) to
/// [`MAX_REPEATED_PENALTY`] (= `1.0`) at [`REPEATED_FAILURE_CAP`] failures,
/// and is clamped there for any higher count.
///
/// ```
/// use ripdpi_runtime_strategy::arm_penalties::repeated_attempt_penalty;
/// assert_eq!(repeated_attempt_penalty(0), 0.0);
/// assert_eq!(repeated_attempt_penalty(5), 1.0);
/// assert_eq!(repeated_attempt_penalty(100), 1.0); // capped
/// ```
pub fn repeated_attempt_penalty(consecutive_failures: u32) -> f64 {
    (consecutive_failures as f64 / REPEATED_FAILURE_CAP as f64).min(MAX_REPEATED_PENALTY)
}

// ── Score application ─────────────────────────────────────────────────────────

/// Apply rarity and repeated-attempt penalties to a base arm score.
///
/// Both penalties are in `[0, 1]`; the result may fall below `base_score` but
/// is not clamped — callers decide how to handle negative final scores.
///
/// ```
/// use ripdpi_runtime_strategy::arm_penalties::apply_penalties;
/// let final_score = apply_penalties(0.75, 0.2, 0.3);
/// assert!((final_score - 0.25).abs() < 1e-12);
/// ```
pub fn apply_penalties(base_score: f64, rarity_pen: f64, repeat_pen: f64) -> f64 {
    base_score - rarity_pen - repeat_pen
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles::{AccessType, DnsClass, IpFamily, NetProfile};

    // ── Fixtures ──────────────────────────────────────────────────────────────

    fn wifi_net() -> NetProfile {
        NetProfile {
            asn: 1234,
            access_type: AccessType::Wifi,
            ip_family: IpFamily::V4,
            dns_class: DnsClass::SystemDefault,
            udp443_ok: true,
            tcp443_ok: true,
            observed_fail_phase: None,
        }
    }

    fn cellular_net() -> NetProfile {
        NetProfile { access_type: AccessType::CellularLte, asn: 5678, ..wifi_net() }
    }

    fn freq(pairs: &[(&str, u32)]) -> HashMap<String, u32> {
        pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect()
    }

    // ── 1. Rare arm gets higher rarity_penalty than common arm ────────────────

    #[test]
    fn rare_arm_has_higher_rarity_penalty_than_common_arm() {
        // "common" selected 100 times, "rare" selected only 1 time.
        let frequencies = freq(&[("common", 100), ("rare", 1), ("mid", 40)]);

        let pen_rare = rarity_penalty(&frequencies, "rare");
        let pen_common = rarity_penalty(&frequencies, "common");

        assert!(pen_rare > pen_common, "rare arm penalty {pen_rare} should exceed common arm penalty {pen_common}");
        // Common arm has max count => penalty 0.
        assert_eq!(pen_common, 0.0, "most-frequent arm should have zero rarity penalty");
        // Rare arm: 1/100 freq => penalty = 1 - 0.01 = 0.99.
        assert!((pen_rare - 0.99).abs() < 1e-12, "rare penalty mismatch: {pen_rare}");
    }

    // ── 2. rarity_penalty resets when network profile changes ─────────────────

    #[test]
    fn rarity_penalty_resets_on_profile_change() {
        // Simulate an arm that looks "common" on wifi (freq 50).
        let wifi_freqs = freq(&[("arm-a", 50), ("arm-b", 100)]);
        let pen_wifi = rarity_penalty(&wifi_freqs, "arm-a");

        // When the network profile changes, the caller resets the freq table.
        // With an empty table the arm reverts to full rarity penalty.
        let new_profile_freqs: HashMap<String, u32> = HashMap::new();
        let pen_new = rarity_penalty(&new_profile_freqs, "arm-a");

        assert!(pen_new > pen_wifi, "penalty after profile change ({pen_new}) must exceed pre-change ({pen_wifi})");
        assert_eq!(pen_new, 1.0, "empty freq table must yield full rarity penalty");
    }

    // ── 3. repeated_attempt_penalty increases monotonically with failures ─────

    #[test]
    fn repeated_attempt_penalty_is_monotonically_increasing() {
        let counts: Vec<u32> = vec![0, 1, 2, 3, 4, 5, 10, 100];
        let penalties: Vec<f64> = counts.iter().map(|&c| repeated_attempt_penalty(c)).collect();

        for window in penalties.windows(2) {
            let prev = window[0];
            let next = window[1];
            assert!(next >= prev, "penalty must be non-decreasing: {prev} -> {next}");
        }
    }

    // ── 4. repeated_attempt_penalty caps after REPEATED_FAILURE_CAP failures ──

    #[test]
    fn repeated_attempt_penalty_caps_at_configured_ceiling() {
        let at_cap = repeated_attempt_penalty(REPEATED_FAILURE_CAP);
        let beyond = repeated_attempt_penalty(REPEATED_FAILURE_CAP + 1);
        let way_beyond = repeated_attempt_penalty(1_000);

        assert_eq!(at_cap, MAX_REPEATED_PENALTY, "at cap must equal MAX_REPEATED_PENALTY");
        assert_eq!(beyond, MAX_REPEATED_PENALTY, "beyond cap must not grow further");
        assert_eq!(way_beyond, MAX_REPEATED_PENALTY, "far beyond cap must still be capped");
    }

    // ── 5. per-(host, NetProfile) independence ────────────────────────────────

    #[test]
    fn repeated_attempt_penalty_is_independent_per_host_and_profile() {
        let net = wifi_net();
        let mut memory = AttemptMemory::new();

        // Hammer host-a with 5 failures (saturated).
        for _ in 0..5 {
            memory.record_failure("host-a.example.com", &net);
        }

        // host-b on same profile: untouched.
        let failures_b = memory.consecutive_failures("host-b.example.com", &net);
        assert_eq!(failures_b, 0, "host-b should have zero failures, got {failures_b}");

        let pen_a = repeated_attempt_penalty(memory.consecutive_failures("host-a.example.com", &net));
        let pen_b = repeated_attempt_penalty(failures_b);

        assert_eq!(pen_a, MAX_REPEATED_PENALTY, "host-a should be fully penalised");
        assert_eq!(pen_b, 0.0, "host-b should be unpenalised");
    }

    // ── 6. rare arm wins tie-break only when posterior is high enough ─────────

    #[test]
    fn rare_arm_beats_common_only_when_posterior_justifies_it() {
        // Two arms with identical base scores from the Bayesian layer.
        // We then apply penalties and check final ordering.
        //
        // arm-popular: selected 100 times (rarity=0.0), posterior=0.6
        // arm-rare:    selected  10 times (rarity=0.9), posterior=0.6
        //
        // With equal posteriors the popular arm should win because it pays no
        // rarity penalty.
        let base = 0.6_f64;
        let freqs = freq(&[("arm-popular", 100), ("arm-rare", 10)]);

        let rarity_pop = rarity_penalty(&freqs, "arm-popular"); // 0.0
        let rarity_rare = rarity_penalty(&freqs, "arm-rare"); // 0.9

        let score_pop = apply_penalties(base, rarity_pop, 0.0);
        let score_rare = apply_penalties(base, rarity_rare, 0.0);

        assert!(
            score_pop > score_rare,
            "popular arm ({score_pop}) should outrank rare arm ({score_rare}) when posteriors are equal"
        );

        // Now give the rare arm a substantially higher posterior so it can
        // overcome the rarity penalty.  posterior_boost > rarity_rare (0.9)
        // is needed.
        let high_posterior_rare = 0.6 + 0.95; // base + enough to cover 0.9 rarity
        let score_rare_boosted = apply_penalties(high_posterior_rare, rarity_rare, 0.0);

        assert!(
            score_rare_boosted > score_pop,
            "rare arm with high posterior ({score_rare_boosted}) must beat popular arm ({score_pop})"
        );
    }

    // ── Extra: success resets failure counter ─────────────────────────────────

    #[test]
    fn success_resets_consecutive_failure_counter() {
        let net = wifi_net();
        let mut memory = AttemptMemory::new();

        memory.record_failure("host.com", &net);
        memory.record_failure("host.com", &net);
        assert_eq!(memory.consecutive_failures("host.com", &net), 2);

        memory.record_success("host.com", &net);
        assert_eq!(memory.consecutive_failures("host.com", &net), 0);
    }

    // ── Extra: profile isolation via NetProfileKey ────────────────────────────

    #[test]
    fn failures_on_wifi_do_not_affect_cellular_entry() {
        let wifi = wifi_net();
        let cell = cellular_net();
        let mut memory = AttemptMemory::new();

        for _ in 0..5 {
            memory.record_failure("host.com", &wifi);
        }

        let cell_failures = memory.consecutive_failures("host.com", &cell);
        assert_eq!(cell_failures, 0, "cellular entry should be independent of wifi failures");
    }

    // ── Extra: clear_for_profile removes only matching entries ───────────────

    #[test]
    fn clear_for_profile_removes_only_that_profiles_entries() {
        let wifi = wifi_net();
        let cell = cellular_net();
        let mut memory = AttemptMemory::new();

        memory.record_failure("host.com", &wifi);
        memory.record_failure("host.com", &cell);

        memory.clear_for_profile(&wifi);

        assert_eq!(memory.consecutive_failures("host.com", &wifi), 0, "wifi entry must be cleared");
        assert_eq!(memory.consecutive_failures("host.com", &cell), 1, "cellular entry must remain");
    }

    // ── Extra: apply_penalties arithmetic ────────────────────────────────────

    #[test]
    fn apply_penalties_subtracts_both_terms() {
        let result = apply_penalties(0.75, 0.20, 0.15);
        assert!((result - 0.40).abs() < 1e-12, "expected 0.40, got {result}");
    }
}
