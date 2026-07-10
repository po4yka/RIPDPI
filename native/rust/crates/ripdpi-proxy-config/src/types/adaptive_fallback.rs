use serde::{Deserialize, Serialize};

use super::constants::{ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4, ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiAdaptiveFallbackConfig {
    #[serde(default = "super::common::default_true")]
    pub enabled: bool,
    #[serde(default = "super::common::default_true")]
    pub torst: bool,
    #[serde(default = "super::common::default_true")]
    pub tls_err: bool,
    #[serde(default = "super::common::default_true")]
    pub http_redirect: bool,
    #[serde(default = "super::common::default_true")]
    pub connect_failure: bool,
    #[serde(default = "super::common::default_true")]
    pub auto_sort: bool,
    #[serde(default = "default_adaptive_fallback_cache_ttl_secs")]
    pub cache_ttl_seconds: i64,
    #[serde(default = "default_adaptive_fallback_cache_prefix_v4")]
    pub cache_prefix_v4: u8,
    #[serde(default)]
    pub strategy_evolution: bool,
    #[serde(
        default = "default_evolution_epsilon_permil",
        rename = "evolutionEpsilon",
        deserialize_with = "deserialize_evolution_epsilon",
        serialize_with = "serialize_evolution_epsilon"
    )]
    pub evolution_epsilon_permil: u32,
    #[serde(default = "default_evolution_experiment_ttl_ms")]
    pub evolution_experiment_ttl_ms: i64,
    #[serde(default = "default_evolution_decay_half_life_ms")]
    pub evolution_decay_half_life_ms: i64,
    #[serde(default = "default_evolution_cooldown_after_failures")]
    pub evolution_cooldown_after_failures: i32,
    #[serde(default = "default_evolution_cooldown_ms")]
    pub evolution_cooldown_ms: i64,
}

impl Default for ProxyUiAdaptiveFallbackConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            torst: true,
            tls_err: true,
            http_redirect: true,
            connect_failure: true,
            auto_sort: true,
            cache_ttl_seconds: default_adaptive_fallback_cache_ttl_secs(),
            cache_prefix_v4: default_adaptive_fallback_cache_prefix_v4(),
            strategy_evolution: false,
            evolution_epsilon_permil: default_evolution_epsilon_permil(),
            evolution_experiment_ttl_ms: default_evolution_experiment_ttl_ms(),
            evolution_decay_half_life_ms: default_evolution_decay_half_life_ms(),
            evolution_cooldown_after_failures: default_evolution_cooldown_after_failures(),
            evolution_cooldown_ms: default_evolution_cooldown_ms(),
        }
    }
}

fn default_adaptive_fallback_cache_ttl_secs() -> i64 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS
}

fn default_adaptive_fallback_cache_prefix_v4() -> u8 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4
}

const fn default_evolution_epsilon_permil() -> u32 {
    100
}

const fn default_evolution_experiment_ttl_ms() -> i64 {
    30_000
}

const fn default_evolution_decay_half_life_ms() -> i64 {
    3_600_000
}

const fn default_evolution_cooldown_after_failures() -> i32 {
    3
}

const fn default_evolution_cooldown_ms() -> i64 {
    300_000
}

fn deserialize_evolution_epsilon<'de, D>(deserializer: D) -> Result<u32, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = f64::deserialize(deserializer)?;
    if !value.is_finite() {
        return Err(serde::de::Error::custom("evolutionEpsilon must be finite"));
    }
    Ok((value.clamp(0.0, 1.0) * 1000.0).round() as u32)
}

#[allow(clippy::trivially_copy_pass_by_ref, reason = "serde serialize_with requires a shared reference")]
fn serialize_evolution_epsilon<S>(value: &u32, serializer: S) -> Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.serialize_f64(f64::from((*value).min(1000)) / 1000.0)
}
