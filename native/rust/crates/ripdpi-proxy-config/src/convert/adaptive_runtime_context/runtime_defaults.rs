use ripdpi_config::{AUTO_RECONN, AUTO_SORT, RuntimeConfig, RuntimeWsTunnelWorkerRoute};

use crate::types::{
    ProxyConfigError, ProxyUiAdaptiveFallbackConfig, ProxyUiHostAutolearnConfig, ProxyUiWsTunnelConfig,
};

pub(crate) fn apply_runtime_section(
    config: &mut RuntimeConfig,
    adaptive_fallback: &ProxyUiAdaptiveFallbackConfig,
    host_autolearn: &ProxyUiHostAutolearnConfig,
    ws_tunnel: &ProxyUiWsTunnelConfig,
) -> Result<(), ProxyConfigError> {
    config.host_autolearn.enabled = host_autolearn.enabled;
    config.host_autolearn.penalty_ttl_secs = host_autolearn.penalty_ttl_hours.max(1).saturating_mul(3600);
    config.host_autolearn.max_hosts = host_autolearn.max_hosts.max(1);
    config.host_autolearn.store_path =
        host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned);
    config.host_autolearn.warmup_probe_enabled = host_autolearn.warmup_probe_enabled;
    config.host_autolearn.network_reprobe_enabled = host_autolearn.network_reprobe_enabled;
    config.adaptive.network_scope_key = host_autolearn
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    config.adaptive.ws_tunnel_mode = match ws_tunnel.mode.as_deref() {
        Some("fallback") => ripdpi_config::WsTunnelMode::Fallback,
        Some("always") => ripdpi_config::WsTunnelMode::Always,
        Some("off" | _) => ripdpi_config::WsTunnelMode::Off,
        None => {
            if ws_tunnel.enabled {
                ripdpi_config::WsTunnelMode::Always
            } else {
                ripdpi_config::WsTunnelMode::Off
            }
        }
    };
    config.adaptive.ws_tunnel_fake_sni = ws_tunnel.fake_sni.clone().filter(|value| !value.is_empty());
    config.adaptive.ws_tunnel_allow_insecure_sni = ws_tunnel.allow_insecure_sni;
    config.adaptive.ws_tunnel_worker_route = parse_worker_route(ws_tunnel)?;
    config.adaptive.auto_level = if adaptive_fallback.enabled { AUTO_RECONN } else { 0 };
    if adaptive_fallback.enabled && adaptive_fallback.auto_sort {
        config.adaptive.auto_level |= AUTO_SORT;
    }
    config.adaptive.cache_ttl = adaptive_fallback.cache_ttl_seconds.max(0);
    config.adaptive.cache_prefix = (32 - adaptive_fallback.cache_prefix_v4.clamp(1, 32)).max(1);
    config.adaptive.strategy_evolution = adaptive_fallback.strategy_evolution;
    config.adaptive.evolution_epsilon_permil = adaptive_fallback.evolution_epsilon_permil.min(1000);
    config.adaptive.evolution_experiment_ttl_ms = adaptive_fallback.evolution_experiment_ttl_ms.max(0) as u64;
    config.adaptive.evolution_decay_half_life_ms = adaptive_fallback.evolution_decay_half_life_ms.max(0) as u64;
    config.adaptive.evolution_cooldown_after_failures =
        adaptive_fallback.evolution_cooldown_after_failures.max(0) as u32;
    config.adaptive.evolution_cooldown_ms = adaptive_fallback.evolution_cooldown_ms.max(0) as u64;
    Ok(())
}

fn parse_worker_route(
    ws_tunnel: &ProxyUiWsTunnelConfig,
) -> Result<Option<RuntimeWsTunnelWorkerRoute>, ProxyConfigError> {
    let url = normalize_optional(&ws_tunnel.cloudflare_worker_url);
    let bearer = normalize_optional(&ws_tunnel.cloudflare_worker_bearer);
    match (url, bearer) {
        (None, None) => Ok(None),
        (Some(url), Some(bearer)) => {
            RuntimeWsTunnelWorkerRoute::parse(url, bearer).map(Some).map_err(ProxyConfigError::InvalidConfig)
        }
        _ => Err(ProxyConfigError::InvalidConfig(
            "Cloudflare Worker WS tunnel requires both cloudflareWorkerUrl and cloudflareWorkerBearer".to_string(),
        )),
    }
}

fn normalize_optional(value: &Option<String>) -> Option<String> {
    value.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned)
}
