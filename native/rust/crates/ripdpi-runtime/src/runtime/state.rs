use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use crate::adaptive_tuning::AdaptivePlannerResolver;
use crate::retry_stealth::RetryPacer;
use crate::runtime_policy::RuntimePolicy;
use crate::RuntimeTelemetrySink;
use ciadpi_config::RuntimeConfig;
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
    pub(super) retry_stealth: Arc<Mutex<RetryPacer>>,
    pub(super) active_clients: Arc<AtomicUsize>,
    pub(super) telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    pub(super) runtime_context: Option<ProxyRuntimeContext>,
}

pub(super) struct RuntimeCleanup {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) cache: Arc<Mutex<RuntimePolicy>>,
}

impl Drop for RuntimeCleanup {
    fn drop(&mut self) {
        let Ok(cache) = self.cache.lock() else {
            return;
        };
        let _ = cache.dump_stdout_groups(&self.config, std::io::stdout());
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
    let (enabled, learned_host_count, penalized_host_count) = cache.autolearn_state(&state.config);
    telemetry.on_host_autolearn_state(enabled, learned_host_count, penalized_host_count);
    for event in cache.drain_autolearn_events() {
        telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
    }
}
