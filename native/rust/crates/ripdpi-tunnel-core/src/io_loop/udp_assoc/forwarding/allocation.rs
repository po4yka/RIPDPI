use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::session::Auth;

use super::super::association_state::UdpAssociation;
use super::super::event_handling::UdpEvent;
use super::super::worker::create_udp_association;

#[allow(clippy::too_many_arguments)]
pub(super) async fn alloc_association(
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    dest: SocketAddr,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let id = *next_id;
    *next_id = next_id.wrapping_add(1);

    create_udp_association(
        proxy_addr,
        auth,
        src,
        dest,
        id,
        idle_timeout,
        protect_path,
        cancel.child_token(),
        udp_tx.clone(),
    )
    .await
}
