use std::sync::Arc;

use super::Stats;

pub(crate) fn set_dns_latency_observer(stats: &Stats, observer: Arc<dyn Fn(u64) + Send + Sync>) {
    if let Ok(mut guard) = stats.dns_latency_observer.lock() {
        *guard = Some(observer);
    }
}

pub(crate) fn notify_dns_latency(stats: &Stats, latency_ms: u64) {
    if let Ok(guard) = stats.dns_latency_observer.lock() {
        if let Some(observer) = guard.as_ref() {
            observer(latency_ms);
        }
    }
}
