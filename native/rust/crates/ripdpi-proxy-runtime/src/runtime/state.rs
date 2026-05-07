use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::{ServicesState, ServicesStateHandle};
use ripdpi_runtime_decision_ports::{AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, RetryPacingPort};
use ripdpi_runtime_decision_ports::{DirectPathLearningPort, PolicyPort};

use mio::Token;

pub(super) const LISTENER: Token = Token(0);
pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Clone)]
pub(super) struct RuntimeState {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) policy: Arc<dyn PolicyPort>,
    pub(super) direct_path_learning: Arc<dyn DirectPathLearningPort>,
    pub(super) adaptive_hints: Arc<dyn AdaptiveHintPort>,
    pub(super) adaptive_feedback: Arc<dyn AdaptiveFeedbackPort>,
    pub(super) adaptive_context: Arc<dyn AdaptiveContextPort>,
    pub(super) retry_pacing: Arc<dyn RetryPacingPort>,
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

impl RuntimeState {
    pub(super) fn new(config: RuntimeConfig, control: Option<std::sync::Arc<EmbeddedProxyControl>>) -> Self {
        let telemetry = control.as_ref().and_then(|c| c.telemetry_sink()).or_else(current_runtime_telemetry);
        let runtime_context = control.as_ref().and_then(|c| c.runtime_context());

        let services = ServicesState::new(config.clone(), telemetry.clone(), runtime_context.clone());
        let handle = ServicesStateHandle::new(services);

        Self {
            config: Arc::new(config),
            policy: Arc::new(handle.clone()),
            direct_path_learning: Arc::new(handle.clone()),
            adaptive_hints: Arc::new(handle.clone()),
            adaptive_feedback: Arc::new(handle.clone()),
            adaptive_context: Arc::new(handle.clone()),
            retry_pacing: Arc::new(handle),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry,
            runtime_context,
            control,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(super::reprobe::ReprobeTracker::new()),
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
        Self::test_with_telemetry_and_context(config, None, runtime_context)
    }

    #[cfg(test)]
    pub(super) fn test_with_telemetry(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    ) -> Self {
        Self::test_with_telemetry_and_context(config, telemetry, None)
    }

    #[cfg(test)]
    pub(super) fn test_with_runtime_policy(
        config: RuntimeConfig,
        runtime_context: Option<ProxyRuntimeContext>,
        _policy: ripdpi_runtime_decision_ports::policy::RuntimePolicy,
    ) -> Self {
        // In tests the policy argument was used to pre-seed route state; the
        // ServicesState equivalent loads from config, which produces the same
        // default state. Tests that need specific learned routes should drive
        // them via the port methods after construction.
        Self::test_with_context(config, runtime_context)
    }

    #[cfg(test)]
    fn test_with_telemetry_and_context(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        let services = ServicesState::new(config.clone(), telemetry.clone(), runtime_context.clone());
        let handle = ServicesStateHandle::new(services);
        Self {
            config: Arc::new(config),
            policy: Arc::new(handle.clone()),
            direct_path_learning: Arc::new(handle.clone()),
            adaptive_hints: Arc::new(handle.clone()),
            adaptive_feedback: Arc::new(handle.clone()),
            adaptive_context: Arc::new(handle.clone()),
            retry_pacing: Arc::new(handle),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry,
            runtime_context,
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(super::reprobe::ReprobeTracker::new()),
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
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
