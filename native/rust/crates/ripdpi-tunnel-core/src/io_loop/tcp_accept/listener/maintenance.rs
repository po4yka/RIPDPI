use std::collections::HashMap;
use std::time::{Duration, Instant};

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::Socket as TcpSocket;
use tracing::debug;

use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::io_loop::tcp_accept::PendingListener;

pub(crate) fn gc_stale_pending_listens(
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    socket_set: &mut SocketSet<'static>,
    timeout: Duration,
) {
    reconcile_pending_listeners(pending_listens, socket_set);
    let now = Instant::now();
    pending_listens.retain(|flow_key, pending| {
        let age = now.duration_since(pending.created_at);
        if age <= timeout {
            return true;
        }
        debug!("GC stale LISTEN socket for flow {} -> {} (age {age:?})", flow_key.src, flow_key.dst);
        socket_set.remove(pending.handle);
        false
    });
}

/// smoltcp LISTEN matches a destination, not the app source. Associate each
/// accepted socket with its actual tuple before any UID-owner cleanup/admission.
pub(crate) fn reconcile_pending_listeners(
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    socket_set: &SocketSet<'static>,
) {
    let accepted: Vec<_> = pending_listens
        .values()
        .filter_map(|listener| {
            let tcp = socket_set.get::<TcpSocket>(listener.handle);
            Some((
                listener.handle,
                TcpFlowKey {
                    src: endpoint_to_socketaddr(tcp.remote_endpoint()?),
                    dst: endpoint_to_socketaddr(tcp.local_endpoint()?),
                },
            ))
        })
        .collect();
    for (handle, actual_key) in accepted {
        if pending_listens.get(&actual_key).is_some_and(|listener| listener.handle == handle) {
            continue;
        }
        let Some(prior_key) =
            pending_listens.iter().find_map(|(key, listener)| (listener.handle == handle).then_some(*key))
        else {
            continue;
        };
        if prior_key == actual_key {
            continue;
        }
        let [Some(prior), Some(actual)] = pending_listens.get_disjoint_mut([&prior_key, &actual_key]) else {
            continue;
        };
        // Swap handles only: attribution generations and timestamps stay with
        // their original exact tuple, including another not-yet-admitted flow.
        std::mem::swap(&mut prior.handle, &mut actual.handle);
    }
}
