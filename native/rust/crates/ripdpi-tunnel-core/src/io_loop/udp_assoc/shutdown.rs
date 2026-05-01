use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::Duration;

use super::association_state::UdpAssociation;

pub(in crate::io_loop) async fn shutdown_udp_associations(udp_associations: &mut HashMap<SocketAddr, UdpAssociation>) {
    for (_src, association) in udp_associations.drain() {
        association.cancel.cancel();
        let _ = tokio::time::timeout(Duration::from_secs(5), association.worker).await;
    }
}
