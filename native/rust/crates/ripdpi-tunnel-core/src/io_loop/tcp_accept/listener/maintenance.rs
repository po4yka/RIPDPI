use std::collections::HashMap;
use std::time::{Duration, Instant};

use smoltcp::iface::{SocketHandle, SocketSet};
use tracing::debug;

use crate::io_loop::packet::TcpFlowKey;

pub(crate) fn gc_stale_pending_listens(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, Instant)>,
    socket_set: &mut SocketSet<'static>,
    timeout: Duration,
) {
    let now = Instant::now();
    pending_listens.retain(|flow_key, (handle, created_at)| {
        let age = now.duration_since(*created_at);
        if age <= timeout {
            return true;
        }
        debug!("GC stale LISTEN socket for flow {} -> {} (age {age:?})", flow_key.src, flow_key.dst);
        socket_set.remove(*handle);
        false
    });
}
