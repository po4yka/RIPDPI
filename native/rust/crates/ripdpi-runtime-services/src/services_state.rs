use std::sync::{Arc, Mutex, RwLock};

use arc_swap::ArcSwap;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::{NetworkSnapshot, ProxyRuntimeContext};
use ripdpi_runtime_adaptive::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use ripdpi_runtime_adaptive::adaptive_tuning::AdaptivePlannerResolver;
use ripdpi_runtime_adaptive::retry_stealth::RetryPacer;
use ripdpi_runtime_api::RuntimeTelemetrySink;
use ripdpi_runtime_policy::direct_path_learning::DirectPathLearningState;
use ripdpi_runtime_policy::runtime_policy::RuntimePolicy;

use crate::strategy_evolution::StrategyEvolutionResolver;

/// A warmup request enqueued via [`BackgroundProbes::request_warmup`].
///
/// Proxy-runtime can receive these from [`ServicesState::take_warmup_receiver`]
/// and execute them through the normal routing pipeline.
#[derive(Debug, Clone)]
pub struct WarmupRequest {
    pub authority: String,
    pub is_udp: bool,
}

// Stateful inventory:
// - cache: route policy, autolearn, and host persistence.
// - adaptive_fake_ttl: fake-TTL resolver feedback.
// - adaptive_tuning: adaptive split/disorder hint feedback and persistence.
// - retry_pacer: reconnect pacing and retry-selection cooldowns.
// - strategy_evolver: strategy-evolution experiment state.
// - direct_path_learning: direct-path UDP/QUIC/TCP reachability state.
//
// Lock order (same as former RuntimeState): cache -> adaptive_fake_ttl -> adaptive_tuning.
// Never acquire more than one simultaneously; if needed, always in this order.
pub struct ServicesState {
    pub(crate) config: Arc<RuntimeConfig>,
    pub(crate) cache: Arc<RwLock<RuntimePolicy>>,
    pub(crate) adaptive_fake_ttl: Arc<RwLock<AdaptiveFakeTtlResolver>>,
    pub(crate) adaptive_tuning: Arc<RwLock<AdaptivePlannerResolver>>,
    pub(crate) retry_pacer: Arc<RwLock<RetryPacer>>,
    pub(crate) strategy_evolver: Arc<RwLock<StrategyEvolutionResolver>>,
    pub(crate) direct_path_learning: Arc<RwLock<DirectPathLearningState>>,
    pub(crate) telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    /// Most-recently pushed OS network snapshot, written by `notify_network_change`.
    /// Lock-free reads via ArcSwap; written infrequently on network transitions.
    pub(crate) network_snapshot: Arc<ArcSwap<Option<NetworkSnapshot>>>,
    /// Tracks the last network identity string so we only surface real network
    /// changes (WiFi→cellular, SSID switch) rather than minor metadata updates.
    pub(crate) last_network_identity: Mutex<Option<String>>,
    /// Sender half of the warmup request channel.
    /// `notify_network_change` and `request_warmup` write here;
    /// proxy-runtime drains via the receiver returned by `take_warmup_receiver`.
    pub(crate) warmup_tx: std::sync::mpsc::SyncSender<WarmupRequest>,
    /// Receiver half — wrapped in `Mutex<Option<…>>` so `take_warmup_receiver`
    /// can hand it out exactly once without requiring `&mut self`.
    warmup_rx: Mutex<Option<std::sync::mpsc::Receiver<WarmupRequest>>>,
}

impl ServicesState {
    pub fn new(
        config: RuntimeConfig,
        telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Arc<Self> {
        let cache = RuntimePolicy::load(&config);
        let adaptive_tuning = AdaptivePlannerResolver::load(&config);
        let strategy_evolver = StrategyEvolutionResolver::from_config(&config);
        // Bounded channel: warmup requests are fire-and-forget; if proxy-runtime
        // is not consuming them the buffer absorbs a small burst before dropping.
        let (warmup_tx, warmup_rx) = std::sync::mpsc::sync_channel(32);
        Arc::new(Self {
            config: Arc::new(config),
            cache: Arc::new(RwLock::new(cache)),
            adaptive_fake_ttl: Arc::new(RwLock::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(RwLock::new(adaptive_tuning)),
            retry_pacer: Arc::new(RwLock::new(RetryPacer::default())),
            strategy_evolver: Arc::new(RwLock::new(strategy_evolver)),
            direct_path_learning: Arc::new(RwLock::new(DirectPathLearningState::default())),
            telemetry,
            runtime_context,
            network_snapshot: Arc::new(ArcSwap::from_pointee(None)),
            last_network_identity: Mutex::new(None),
            warmup_tx,
            warmup_rx: Mutex::new(Some(warmup_rx)),
        })
    }

    /// Take the warmup receiver for consumption by proxy-runtime.
    ///
    /// Returns `Some` on the first call and `None` on all subsequent calls.
    /// Proxy-runtime should call this once during initialisation and drive
    /// the returned receiver on its warmup thread.
    pub fn take_warmup_receiver(&self) -> Option<std::sync::mpsc::Receiver<WarmupRequest>> {
        self.warmup_rx.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
    }
}

impl Drop for ServicesState {
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
