use std::collections::HashMap;
use std::net::SocketAddr;

use super::association_state::UdpAssociation;

pub(super) fn remove_association(associations: &mut HashMap<SocketAddr, UdpAssociation>, src: SocketAddr) {
    if let Some(association) = associations.remove(&src) {
        association.cancel.cancel();
        // Drop the per-app attribution cache entry so a later flow to the same
        // destination (possibly a different app) re-resolves its owner.
        ripdpi_flow_app_attribution::evict_flow(association.dest.ip());
    }
}
