use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio_util::sync::CancellationToken;

use crate::session::UdpSession;

/// Returns milliseconds since the Unix epoch, or 0 on clock failure.
pub(super) fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub(in crate::io_loop) struct UdpAssociation {
    pub(super) id: u64,
    pub(super) session: UdpSession,
    pub(super) cancel: CancellationToken,
    pub(super) last_activity: Arc<AtomicU64>,
    pub(super) worker: tokio::task::JoinHandle<()>,
}

pub(super) fn touch_udp_activity(last_activity: &Arc<AtomicU64>) {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    last_activity.store(now_millis(), Ordering::Relaxed);
}

pub(super) fn udp_association_is_idle(last_activity: &Arc<AtomicU64>, idle_timeout: Duration) -> bool {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    now_millis().saturating_sub(last_activity.load(Ordering::Relaxed)) >= idle_timeout.as_millis() as u64
}

pub(super) fn remove_association(associations: &mut HashMap<SocketAddr, UdpAssociation>, src: SocketAddr) {
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
    }
}
