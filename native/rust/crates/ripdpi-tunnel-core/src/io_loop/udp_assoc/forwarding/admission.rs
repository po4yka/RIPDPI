use std::collections::HashMap;
use std::net::SocketAddr;

use super::super::association_state::{UDP_DESTINATION_CAPACITY, UdpAssociation};

pub(super) fn can_admit_udp_destination(
    associations: &HashMap<SocketAddr, UdpAssociation>,
    src: SocketAddr,
    dst: SocketAddr,
) -> bool {
    let Some(association) = associations.get(&src) else {
        return false;
    };
    if association.destinations.contains(&dst) {
        return true;
    }
    association.destinations.len() < UDP_DESTINATION_CAPACITY
}
