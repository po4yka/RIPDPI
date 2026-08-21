use std::fs;
use std::io;
use std::path::Path;
use std::sync::{OnceLock, RwLock};

use ripdpi_config::{EntropyMode, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_failure_classifier::FailureClass;
use serde::{Deserialize, Serialize};

use super::StrategyEvolver;
use super::selection::combo_matches_bucket;
use super::types::{ComboStats, LearningContext, StrategyCombo, combo_fitness_at, now_millis};

pub const PROBE_OBSERVATION_WEIGHT: u32 = 3;

static GLOBAL_PROBE_RESULTS: OnceLock<RwLock<GlobalProbeResults>> = OnceLock::new();

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProbeResult {
    pub strategy_id: String,
    pub domain: String,
    pub success: bool,
    pub latency_ms: u64,
}

impl ProbeResult {
    pub fn success(strategy_id: impl Into<String>, domain: impl Into<String>, latency_ms: u64) -> Self {
        Self { strategy_id: strategy_id.into(), domain: domain.into(), success: true, latency_ms }
    }

    pub fn failure(strategy_id: impl Into<String>, domain: impl Into<String>, latency_ms: u64) -> Self {
        Self { strategy_id: strategy_id.into(), domain: domain.into(), success: false, latency_ms }
    }
}

#[derive(Debug)]
pub enum ProbeResultsError {
    Io(io::Error),
    Json(serde_json::Error),
}

impl std::fmt::Display for ProbeResultsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(f, "{error}"),
            Self::Json(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for ProbeResultsError {}

impl From<io::Error> for ProbeResultsError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<serde_json::Error> for ProbeResultsError {
    fn from(error: serde_json::Error) -> Self {
        Self::Json(error)
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct LocalPriorRecord {
    strategy_id: String,
    attempts: u32,
    successes: u32,
    total_latency_ms: u64,
    total_latency_square_ms: u128,
    last_attempt_ms: u64,
}

#[derive(Debug, Default, Clone)]
struct GlobalProbeResults {
    generation: u64,
    results: Vec<ProbeResult>,
}

fn global_probe_results() -> &'static RwLock<GlobalProbeResults> {
    GLOBAL_PROBE_RESULTS.get_or_init(|| RwLock::new(GlobalProbeResults::default()))
}

impl StrategyEvolver {
    pub fn inject_probe_results(&mut self, results: &[ProbeResult]) {
        let context = self.current_learning_context.clone();
        for result in results {
            let Some(combo) = probe_combo_for_strategy_id(&result.strategy_id) else {
                continue;
            };
            if !combo_matches_bucket(&combo, context.target_bucket) {
                continue;
            }
            for _ in 0..PROBE_OBSERVATION_WEIGHT {
                self.record_probe_result(&context, &combo, result);
            }
        }
        self.clear_pending_probe_selection();
    }

    pub fn combo_stats_for(&self, combo: &StrategyCombo) -> Option<&ComboStats> {
        self.combos.get(combo)
    }

    pub fn save_local_priors(&self, path: impl AsRef<Path>) -> Result<(), ProbeResultsError> {
        let records =
            self.combos.iter().filter_map(|(combo, stats)| local_prior_record(combo, stats)).collect::<Vec<_>>();
        let path = path.as_ref();
        let mut document = read_prior_document(path)?;
        document.insert("local_priors".to_owned(), serde_json::to_value(records)?);
        // Wall-clock stamp for the whole batch: `last_attempt_ms` values are
        // evolver-epoch-relative and die with the process, so the loader
        // needs an absolute reference to reconstruct their age.
        document.insert("local_priors_saved_at_unix_ms".to_owned(), serde_json::to_value(now_millis())?);
        fs::write(path, serde_json::to_vec_pretty(&serde_json::Value::Object(document))?)?;
        Ok(())
    }

    /// Load locally persisted priors. The document's
    /// `local_priors_saved_at_unix_ms` stamp (written by
    /// [`Self::save_local_priors`]) is converted into a wall-clock age that
    /// is baked into the counters via [`ComboStats::apply_decay`]; records
    /// are then anchored at the new evolver epoch. Documents without the
    /// stamp keep the pre-stamp behaviour of trusting the stored counters.
    pub fn load_local_priors(&mut self, path: impl AsRef<Path>) -> Result<usize, ProbeResultsError> {
        let bytes = fs::read(path)?;
        let document = serde_json::from_slice::<serde_json::Value>(&bytes)?;
        let records = serde_json::from_value::<Vec<LocalPriorRecord>>(
            document.get("local_priors").cloned().unwrap_or_else(|| serde_json::Value::Array(Vec::new())),
        )?;
        let saved_at_unix_ms = document.get("local_priors_saved_at_unix_ms").and_then(serde_json::Value::as_u64);
        let age_ms = stored_age_ms(saved_at_unix_ms);
        let context = self.current_learning_context.clone();
        let mut loaded = 0usize;
        for record in records {
            let Some(combo) = probe_combo_for_strategy_id(&record.strategy_id) else {
                continue;
            };
            let stats = stats_from_local_prior_with_age(&record, age_ms);
            self.combos.insert(combo.clone(), stats.clone());
            self.seed_context_prior(&context, &combo, stats);
            loaded += 1;
        }
        self.clear_pending_probe_selection();
        Ok(loaded)
    }

    fn record_probe_result(&mut self, context: &LearningContext, combo: &StrategyCombo, result: &ProbeResult) {
        let now_ms = self.monotonic_now_ms();
        let failure_class = (!result.success).then_some(FailureClass::ConnectFailure);
        self.evict_if_needed(combo, now_ms);
        let last_attempt_ms = {
            let stats = self.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
            let _ = stats.record_attempt(
                result.success,
                result.latency_ms,
                failure_class,
                now_ms,
                self.cooldown_after_failures,
                self.cooldown_ms,
            );
            stats.last_attempt_ms
        };
        self.record_contextual_feedback(
            context,
            combo.family(),
            combo,
            result.success,
            result.latency_ms,
            failure_class,
            last_attempt_ms,
        );
    }

    fn seed_context_prior(&mut self, context: &LearningContext, combo: &StrategyCombo, stats: ComboStats) {
        let now_ms = self.monotonic_now_ms();
        let state = self.contexts.entry(context.clone()).or_default();
        state.piloted_buckets.insert(context.target_bucket);
        state.combos.insert(combo.clone(), stats.clone());
        state.niche_winners.insert(context.target_bucket, combo.clone());
        let family_stats = state.families.entry(combo.family()).or_default();
        family_stats.attempts = family_stats.attempts.saturating_add(stats.attempts);
        family_stats.total_reward += combo_fitness_at(combo, &stats, now_ms, self.decay_half_life_ms);
    }

    fn clear_pending_probe_selection(&mut self) {
        self.current_experiment = None;
        self.current_experiment_context = None;
        self.current_experiment_family = None;
        self.current_experiment_started_ms = None;
    }
}

fn read_prior_document(path: &Path) -> Result<serde_json::Map<String, serde_json::Value>, ProbeResultsError> {
    match fs::read(path) {
        Ok(bytes) if bytes.is_empty() => Ok(serde_json::Map::new()),
        Ok(bytes) => Ok(serde_json::from_slice::<serde_json::Map<String, serde_json::Value>>(&bytes)?),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(serde_json::Map::new()),
        Err(error) => Err(error.into()),
    }
}

pub fn apply_global_probe_results(results: &[ProbeResult]) -> usize {
    if results.is_empty() {
        return 0;
    }
    let mut global = global_probe_results().write().expect("probe result registry poisoned");
    global.generation = global.generation.saturating_add(1);
    global.results = results.to_vec();
    results.len()
}

pub fn latest_global_probe_results() -> (u64, Vec<ProbeResult>) {
    let global = global_probe_results().read().expect("probe result registry poisoned");
    (global.generation, global.results.clone())
}

#[doc(hidden)]
pub fn clear_global_probe_results_for_tests() {
    let mut global = global_probe_results().write().expect("probe result registry poisoned");
    *global = GlobalProbeResults::default();
}

pub fn probe_combo_for_strategy_id(strategy_id: &str) -> Option<StrategyCombo> {
    match normalize_strategy_id(strategy_id).as_str() {
        "split" => Some(split_combo()),
        "fake" => Some(StrategyCombo { fake_ttl: Some(8), ..split_combo() }),
        "oob" => Some(StrategyCombo { entropy_mode: Some(EntropyMode::Popcount), ..split_combo() }),
        "tls_rec_split" | "tlsrec_split" => {
            Some(StrategyCombo { tls_record_offset_base: Some(OffsetBase::AutoHost), ..split_combo() })
        }
        "tls_rand_rec" | "tlsrandrec_split" => {
            Some(StrategyCombo { tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Balanced), ..split_combo() })
        }
        "udp_fake_burst" | "fake_burst" => {
            Some(StrategyCombo { udp_burst_profile: Some(AdaptiveUdpBurstProfile::Balanced), ..split_combo() })
        }
        "quic_fake_version" | "quic_compat_burst" => {
            Some(StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::CompatDefault), ..split_combo() })
        }
        "quic_realistic_burst" => {
            Some(StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::RealisticInitial), ..split_combo() })
        }
        _ => None,
    }
}

fn split_combo() -> StrategyCombo {
    StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() }
}

fn local_prior_record(combo: &StrategyCombo, stats: &ComboStats) -> Option<LocalPriorRecord> {
    Some(LocalPriorRecord {
        strategy_id: strategy_id_for_probe_combo(combo)?.to_owned(),
        attempts: stats.attempts,
        successes: stats.successes,
        total_latency_ms: stats.total_latency_ms,
        total_latency_square_ms: stats.total_latency_square_ms,
        last_attempt_ms: stats.last_attempt_ms,
    })
}

fn stats_from_local_prior(record: &LocalPriorRecord) -> ComboStats {
    ComboStats {
        attempts: record.attempts,
        successes: record.successes.min(record.attempts),
        total_latency_ms: record.total_latency_ms,
        total_latency_square_ms: record.total_latency_square_ms,
        last_attempt_ms: record.last_attempt_ms,
        ..ComboStats::new()
    }
}

/// Wall-clock age of a saved priors batch. `None` (legacy documents without
/// a stamp) yields zero age, preserving the pre-stamp behaviour.
fn stored_age_ms(saved_at_unix_ms: Option<u64>) -> u64 {
    saved_at_unix_ms.map_or(0, |saved_at| now_millis().saturating_sub(saved_at))
}

fn stats_from_local_prior_with_age(record: &LocalPriorRecord, age_ms: u64) -> ComboStats {
    let mut stats = stats_from_local_prior(record);
    // Bake the stored age into the counters so decay reflects reality even
    // though the new evolver's monotonic clock restarts at zero and cannot
    // represent ages larger than its own uptime.
    stats.apply_decay(age_ms);
    // The age is now encoded in the decayed counters; anchoring at the new
    // epoch start prevents double-decay from a meaningless cross-session
    // timestamp while letting idle decay continue forward from load time.
    stats.last_attempt_ms = 0;
    stats
}

fn strategy_id_for_probe_combo(combo: &StrategyCombo) -> Option<&'static str> {
    if combo.fake_ttl.is_some() {
        Some("fake")
    } else if combo.tls_record_offset_base.is_some() {
        Some("tls_rec_split")
    } else if combo.tlsrandrec_profile.is_some() {
        Some("tls_rand_rec")
    } else if combo.udp_burst_profile.is_some() {
        Some("udp_fake_burst")
    } else if combo.quic_fake_profile == Some(QuicFakeProfile::RealisticInitial) {
        Some("quic_realistic_burst")
    } else if combo.quic_fake_profile.is_some() {
        Some("quic_compat_burst")
    } else if combo.entropy_mode.is_some() {
        Some("oob")
    } else if combo.split_offset_base.is_some() {
        Some("split")
    } else {
        None
    }
}

fn normalize_strategy_id(strategy_id: &str) -> String {
    strategy_id.trim().trim_start_matches("lua:").replace('-', "_").to_ascii_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn global_probe_results_replace_previous_batch() {
        clear_global_probe_results_for_tests();
        let first_batch = [ProbeResult::success("tls_rec_split", "first.example", 40)];
        let second_batch = [ProbeResult::success("split", "second.example", 25)];

        assert_eq!(apply_global_probe_results(&first_batch), first_batch.len());
        assert_eq!(apply_global_probe_results(&second_batch), second_batch.len());
        assert_eq!(apply_global_probe_results(&[]), 0);

        let (generation, results) = latest_global_probe_results();
        assert_eq!(generation, 2);
        assert_eq!(results, second_batch);
        clear_global_probe_results_for_tests();
    }
}
