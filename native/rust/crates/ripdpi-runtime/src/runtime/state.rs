use crate::sync::{Arc, AtomicBool, AtomicUsize, Mutex, Ordering, RwLock};
use std::time::Duration;

use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use crate::adaptive_tuning::AdaptivePlannerResolver;
use crate::retry_stealth::RetryPacer;
use crate::runtime_policy::RuntimePolicy;
use crate::strategy_evolver::StrategyEvolver;
use crate::{EmbeddedProxyControl, RuntimeTelemetrySink};
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;

use mio::Token;

pub(super) const LISTENER: Token = Token(0);
pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
pub(super) const DESYNC_SEED_BASE: u32 = 7;

#[derive(Clone)]
pub(super) struct RuntimeState {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) cache: Arc<Mutex<RuntimePolicy>>,
    pub(super) adaptive_fake_ttl: Arc<Mutex<AdaptiveFakeTtlResolver>>,
    pub(super) adaptive_tuning: Arc<Mutex<AdaptivePlannerResolver>>,
    pub(super) retry_stealth: Arc<RwLock<RetryPacer>>,
    pub(super) strategy_evolver: Arc<RwLock<StrategyEvolver>>,
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
    pub(super) pcap_hook: Option<super::desync::PcapHook>,
    /// io_uring driver for zero-copy relay (Linux 6.0+, optional).
    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(super) io_uring: Option<std::sync::Arc<ripdpi_io_uring::IoUringDriver>>,
}

pub(super) struct RuntimeCleanup {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) cache: Arc<Mutex<RuntimePolicy>>,
    pub(super) adaptive_tuning: Arc<Mutex<AdaptivePlannerResolver>>,
}

impl Drop for RuntimeCleanup {
    fn drop(&mut self) {
        if let Ok(mut cache) = self.cache.lock() {
            cache.flush_host_store(&self.config);
            let _ = cache.dump_stdout_groups(&self.config, std::io::stdout());
        }
        if let Ok(mut adaptive_tuning) = self.adaptive_tuning.lock() {
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
