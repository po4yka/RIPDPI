use ripdpi_monitor_adapter::proxy_config::{ProxyRuntimeContext, runtime_config_from_ui};
use std::sync::atomic::{AtomicU64, Ordering};

use crate::candidates::StrategyCandidateSpec;

use super::adaptive_freeze::freeze_adaptive_fake_ttl_for_probe;
use super::contracts::{CandidateRuntimeError, PreparedCandidateRuntime};

static NEXT_CANDIDATE_GENERATION: AtomicU64 = AtomicU64::new(1);

pub(crate) fn prepare_candidate_runtime(
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<PreparedCandidateRuntime, CandidateRuntimeError> {
    let mut runtime_config = spec.config.clone();
    runtime_config.listen.ip = "127.0.0.1".to_string();
    runtime_config.host_autolearn.enabled = false;
    runtime_config.host_autolearn.store_path = None;
    if !spec.preserve_adaptive_fake_ttl {
        freeze_adaptive_fake_ttl_for_probe(&mut runtime_config);
    }
    let mut config = runtime_config_from_ui(runtime_config).map_err(|err| {
        tracing::warn!(candidate = spec.id, error = %err, "probe runtime config validation failed");
        CandidateRuntimeError::Preparation(err.to_string())
    })?;
    let _ = ripdpi_monitor_adapter::proxy_config::presets::apply_runtime_preset("ripdpi_default", &mut config);
    config.network.listen.listen_port = 0;
    if let Some(ctx) = runtime_context
        && let Some(ref path) = ctx.protect_path
    {
        config.process.protect_path = Some(path.clone());
    }
    // Relaxed is sufficient: the counter supplies identity only; runtime publication and joins provide ordering.
    let generation = NEXT_CANDIDATE_GENERATION
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |current| current.checked_add(1).filter(|next| *next != 0))
        .map_err(|_| CandidateRuntimeError::Preparation("candidate generation space exhausted".to_string()))?;
    Ok(PreparedCandidateRuntime { config, runtime_context: runtime_context.cloned(), generation })
}
