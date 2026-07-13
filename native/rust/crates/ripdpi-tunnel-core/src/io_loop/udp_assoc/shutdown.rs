use std::collections::HashMap;
use std::net::SocketAddr;

use super::association_state::UdpAssociation;
use crate::io_loop::task_shutdown::{TASK_SHUTDOWN_GRACE, drain_tasks};

pub(in crate::io_loop) async fn shutdown_udp_associations(udp_associations: &mut HashMap<SocketAddr, UdpAssociation>) {
    let mut tasks = Vec::with_capacity(udp_associations.len());
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        tasks.push(association.worker);
    }
    drain_tasks(tasks, TASK_SHUTDOWN_GRACE).await;
}
