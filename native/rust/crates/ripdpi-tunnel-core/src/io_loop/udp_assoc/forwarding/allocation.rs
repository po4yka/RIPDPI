use std::net::SocketAddr;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::worker::spawn_udp_association;

#[allow(clippy::too_many_arguments)]
pub(super) fn alloc_association(
    next_id: &mut u64,
    memory_budget: &UdpMemoryBudget,
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    dest: SocketAddr,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) -> UdpAssociation {
    let id = *next_id;
    *next_id = next_id.wrapping_add(1);

    spawn_udp_association(
        proxy_addr,
        auth,
        src,
        dest,
        id,
        idle_timeout,
        protect_path,
        memory_budget.clone(),
        cancel.child_token(),
        udp_tx.clone(),
    )
}
