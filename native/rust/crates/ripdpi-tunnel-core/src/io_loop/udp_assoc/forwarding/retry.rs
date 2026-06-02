use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::session::Auth;

use super::super::association_removal::remove_association;
use super::super::association_state::{UdpAssociation, touch_udp_activity};
use super::super::event_handling::UdpEvent;
use super::allocation::alloc_association;

#[allow(clippy::too_many_arguments)]
pub(super) async fn retry_udp_send_with_new_association(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    next_id: &mut u64,
    proxy_addr: SocketAddr,
    auth: &Auth,
    src: SocketAddr,
    resolved_dst: SocketAddr,
    payload: &[u8],
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: &CancellationToken,
    udp_tx: &tokio::sync::mpsc::Sender<UdpEvent>,
) {
    remove_association(associations, src);
    let Ok(association) = alloc_association(
        next_id,
        proxy_addr,
        auth.clone(),
        src,
        resolved_dst,
        idle_timeout,
        protect_path,
        cancel,
        udp_tx,
    )
    .await
    else {
        return;
    };

    let retry = association.session.clone();
    touch_udp_activity(&association.last_activity);
    associations.insert(src, association);
    if retry.send_to(resolved_dst, payload).await.is_err() {
        remove_association(associations, src);
    }
}
