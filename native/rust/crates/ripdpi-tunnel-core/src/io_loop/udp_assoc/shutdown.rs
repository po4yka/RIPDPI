use std::collections::HashMap;
use std::net::SocketAddr;

use super::association_state::UdpAssociation;
use crate::dns_cache::DnsCache;
use crate::io_loop::task_shutdown::{TASK_SHUTDOWN_GRACE, drain_tasks};

pub(in crate::io_loop) fn take_udp_association_tasks(
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
) -> Vec<tokio::task::JoinHandle<()>> {
    let mut tasks = Vec::with_capacity(udp_associations.len());
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
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

pub(in crate::io_loop) async fn drain_udp_association_tasks(tasks: Vec<tokio::task::JoinHandle<()>>) {
    drain_tasks(tasks, TASK_SHUTDOWN_GRACE).await;
}

#[cfg(test)]
pub(in crate::io_loop) async fn shutdown_udp_associations(
    udp_associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
) {
    let tasks = take_udp_association_tasks(udp_associations, dns_cache);
    drain_udp_association_tasks(tasks).await;
}
