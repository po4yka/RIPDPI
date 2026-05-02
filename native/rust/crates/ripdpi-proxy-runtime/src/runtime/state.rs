use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering, RwLock};
use std::time::Duration;

use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_adaptive::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use ripdpi_runtime_adaptive::adaptive_tuning::AdaptivePlannerResolver;
use ripdpi_runtime_adaptive::retry_stealth::RetryPacer;
use ripdpi_runtime_adaptive::strategy_evolution::StrategyEvolutionResolver;
use ripdpi_runtime_api::{current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink};
use ripdpi_runtime_policy::direct_path_learning::DirectPathLearningState;
use ripdpi_runtime_policy::runtime_policy::RuntimePolicy;

use mio::Token;

pub(super) const LISTENER: Token = Token(0);
pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

// Lock order: cache -> adaptive_fake_ttl -> adaptive_tuning
// No function should acquire more than one of these locks simultaneously, but
// if that ever changes, always acquire in the order listed above to prevent
// deadlocks.
#[derive(Clone)]
pub(super) struct RuntimeState {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) cache: Arc<RwLock<RuntimePolicy>>,
    pub(super) adaptive_fake_ttl: Arc<RwLock<AdaptiveFakeTtlResolver>>,
    pub(super) adaptive_tuning: Arc<RwLock<AdaptivePlannerResolver>>,
    pub(super) retry_stealth: Arc<RwLock<RetryPacer>>,
    pub(super) strategy_evolver: Arc<RwLock<StrategyEvolutionResolver>>,
    pub(super) direct_path_learning: Arc<RwLock<DirectPathLearningState>>,
    pub(super) active_clients: Arc<AtomicUsize>,
    pub(super) telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    pub(super) runtime_context: Option<ProxyRuntimeContext>,
    pub(super) control: Option<std::sync::Arc<EmbeddedProxyControl>>,
    /// Session-level flag: once any connection discovers that per-socket TTL
    /// modification is rejected by the kernel (EROFS on Android), all
    /// subsequent connections skip TTL desync actions immediately.
    pub(super) ttl_unavailable: Arc<AtomicBool>,
    /// Tracks network scope key changes for lightweight re-probing.
    pub(super) reprobe_tracker: std::sync::Arc<super::reprobe::ReprobeTracker>,
    #[allow(dead_code)]
    pub(super) dns_hostname_cache: std::sync::Arc<ripdpi_runtime_dns_cache::dns_hostname_cache::DnsHostnameCache>,
    pub(super) pcap_hook: Option<super::desync::PcapHook>,
    /// io_uring driver for zero-copy relay (Linux 6.0+, optional).
    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(super) io_uring: Option<std::sync::Arc<ripdpi_io_uring::IoUringDriver>>,
}

impl RuntimeState {
    pub(super) fn new(config: RuntimeConfig, control: Option<std::sync::Arc<EmbeddedProxyControl>>) -> Self {
        let cache = RuntimePolicy::load(&config);
        let adaptive_tuning = AdaptivePlannerResolver::load(&config);
        let telemetry = control.as_ref().and_then(|value| value.telemetry_sink()).or_else(current_runtime_telemetry);
        let runtime_context = control.as_ref().and_then(|value| value.runtime_context());
        Self::from_parts(config, RuntimeStateParts { cache, adaptive_tuning, telemetry, runtime_context, control })
    }

    fn from_parts(config: RuntimeConfig, parts: RuntimeStateParts) -> Self {
        let strategy_evolver = StrategyEvolutionResolver::from_config(&config);
        Self {
            config: Arc::new(config),
            cache: Arc::new(RwLock::new(parts.cache)),
            adaptive_fake_ttl: Arc::new(RwLock::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(RwLock::new(parts.adaptive_tuning)),
            retry_stealth: Arc::new(RwLock::new(RetryPacer::default())),
            strategy_evolver: Arc::new(RwLock::new(strategy_evolver)),
            direct_path_learning: Arc::new(RwLock::new(DirectPathLearningState::default())),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry: parts.telemetry,
            runtime_context: parts.runtime_context,
            control: parts.control,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(super::reprobe::ReprobeTracker::new()),
            dns_hostname_cache: std::sync::Arc::new(
                ripdpi_runtime_dns_cache::dns_hostname_cache::DnsHostnameCache::with_default_capacity(),
            ),
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }

    #[cfg(test)]
    pub(super) fn test(config: RuntimeConfig) -> Self {
        Self::test_with_context(config, None)
    }

    #[cfg(test)]
    pub(super) fn test_with_context(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> Self {
        Self::test_with_overrides(config, runtime_context, None, RuntimePolicy::load)
    }

    #[cfg(test)]
    pub(super) fn test_with_telemetry(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    ) -> Self {
        Self::test_with_overrides(config, None, telemetry, RuntimePolicy::load)
    }

    #[cfg(test)]
    pub(super) fn test_with_runtime_policy(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
        policy: RuntimePolicy,
    ) -> Self {
        Self::test_with_parts(config, runtime_context, None, policy)
    }

    #[cfg(test)]
    fn test_with_overrides(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        policy_loader: impl FnOnce(&RuntimeConfig) -> RuntimePolicy,
    ) -> Self {
        let policy = policy_loader(&config);
        Self::test_with_parts(config, runtime_context, telemetry, policy)
    }

    #[cfg(test)]
    fn test_with_parts(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        policy: RuntimePolicy,
    ) -> Self {
        Self::from_parts(
            config,
            RuntimeStateParts {
                cache: policy,
                adaptive_tuning: AdaptivePlannerResolver::default(),
                telemetry,
                runtime_context,
                control: None,
            },
        )
    }
}

struct RuntimeStateParts {
    cache: RuntimePolicy,
    adaptive_tuning: AdaptivePlannerResolver,
    telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    control: Option<std::sync::Arc<EmbeddedProxyControl>>,
}

pub(super) struct RuntimeCleanup {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) cache: Arc<RwLock<RuntimePolicy>>,
    pub(super) adaptive_tuning: Arc<RwLock<AdaptivePlannerResolver>>,
}

impl RuntimeCleanup {
    pub(super) fn from_state(state: &RuntimeState) -> Self {
        Self {
            config: state.config.clone(),
            cache: state.cache.clone(),
            adaptive_tuning: state.adaptive_tuning.clone(),
        }
    }
}

impl Drop for RuntimeCleanup {
    fn drop(&mut self) {
        if let Ok(mut cache) = self.cache.write() {
            cache.flush_host_store(&self.config);
            let _ = cache.dump_stdout_groups(&self.config, std::io::stdout());
        }
        if let Ok(mut adaptive_tuning) = self.adaptive_tuning.write() {
            adaptive_tuning.flush_store(self.config.as_ref());
        }
    }
}

pub(super) struct ClientSlotGuard {
    active: Arc<AtomicUsize>,
}

impl ClientSlotGuard {
    pub(super) fn acquire(active: Arc<AtomicUsize>, limit: usize) -> Option<Self> {
        loop {
            let current = active.load(Ordering::Relaxed);
            if current >= limit {
                return None;
            }
            if active.compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed).is_ok() {
                return Some(Self { active });
            }
        }
    }
}

impl Drop for ClientSlotGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

pub(super) fn flush_autolearn_updates(state: &RuntimeState, cache: &mut RuntimePolicy) {
    let Some(telemetry) = &state.telemetry else {
        let _ = cache.drain_autolearn_events();
        return;
    };
    let autolearn = cache.autolearn_state(&state.config);
    telemetry.on_host_autolearn_state(
        autolearn.enabled,
        autolearn.learned_host_count,
        autolearn.penalized_host_count,
        autolearn.blocked_host_count,
        autolearn.last_block_signal.as_deref(),
        autolearn.last_block_provider.as_deref(),
    );
    for event in cache.drain_autolearn_events() {
        telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
    }
}
