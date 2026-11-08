use crate::io_loop::udp_assoc::UdpAssociations;

use crate::dns_cache::DnsCache;
use crate::io_loop::task_shutdown::{TASK_SHUTDOWN_GRACE, drain_tasks};

pub(in crate::io_loop) fn take_udp_association_tasks(
    udp_associations: &mut UdpAssociations,
    dns_cache: &mut Option<DnsCache>,
) -> Vec<tokio::task::JoinHandle<()>> {
    let mut tasks = Vec::with_capacity(udp_associations.len());
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        for destination in association.destinations.values() {
            destination.retire();
        }
        if let Some(cache) = dns_cache.as_mut() {
            for ip in association.leased_synthetic_ips {
                cache.unpin(ip);
            }
        }
        for (_request, registration_id) in association.attribution_ids {
            ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
        }
        tasks.push(association.worker);
    }
    tasks
}

/// # Cancel safety:
/// cancel-safe: the drain puts unfinished tasks behind an abort-on-drop guard before awaiting.
pub(in crate::io_loop) async fn drain_udp_association_tasks(tasks: Vec<tokio::task::JoinHandle<()>>) {
    drain_tasks(tasks, TASK_SHUTDOWN_GRACE).await;
}

#[cfg(test)]
/// # Cancel safety:
/// cancel-safe: indexes and leases are released synchronously; the drain aborts unfinished workers on drop.
pub(in crate::io_loop) async fn shutdown_udp_associations(
    udp_associations: &mut UdpAssociations,
    dns_cache: &mut Option<DnsCache>,
) {
    let tasks = take_udp_association_tasks(udp_associations, dns_cache);
    drain_udp_association_tasks(tasks).await;
}
