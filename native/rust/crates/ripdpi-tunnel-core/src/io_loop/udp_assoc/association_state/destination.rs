use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use crate::session::TargetAddr;

use super::activity::{activity_is_idle_at, now_millis, touch_udp_activity};

pub(in crate::io_loop) struct UdpDestination {
    pub(in crate::io_loop::udp_assoc) target: TargetAddr,
    pub(in crate::io_loop::udp_assoc) resolved_dest: SocketAddr,
    pub(in crate::io_loop::udp_assoc) response_source: SocketAddr,
    pub(in crate::io_loop::udp_assoc) synthetic_ip: Option<u32>,
    pub(in crate::io_loop::udp_assoc) last_activity: AtomicU64,
    live: AtomicBool,
}

impl UdpDestination {
    pub(in crate::io_loop::udp_assoc) fn new(
        target: TargetAddr,
        resolved_dest: SocketAddr,
        response_source: SocketAddr,
        synthetic_ip: Option<u32>,
    ) -> Self {
        Self::new_at(target, resolved_dest, response_source, synthetic_ip, now_millis())
    }

    pub(in crate::io_loop::udp_assoc) fn new_at(
        target: TargetAddr,
        resolved_dest: SocketAddr,
        response_source: SocketAddr,
        synthetic_ip: Option<u32>,
        now: u64,
    ) -> Self {
        Self {
            target,
            resolved_dest,
            response_source,
            synthetic_ip,
            last_activity: AtomicU64::new(now),
            live: AtomicBool::new(true),
        }
    }

    pub(in crate::io_loop::udp_assoc) fn is_live(&self) -> bool {
        // Ordering: Relaxed -- the flag publishes no data; immutable metadata travels through Arc/channel ownership.
        self.live.load(Ordering::Relaxed)
    }

    pub(in crate::io_loop::udp_assoc) fn retire(&self) {
        // Ordering: Relaxed -- this only invalidates identity; it does not publish mutable metadata.
        self.live.store(false, Ordering::Relaxed);
    }

    pub(in crate::io_loop::udp_assoc) fn touch(&self) {
        if self.is_live() {
            touch_udp_activity(&self.last_activity);
        }
    }

    pub(in crate::io_loop::udp_assoc) fn is_idle_at(&self, idle_timeout: Duration, now: u64) -> bool {
        activity_is_idle_at(&self.last_activity, idle_timeout, now)
    }
}
