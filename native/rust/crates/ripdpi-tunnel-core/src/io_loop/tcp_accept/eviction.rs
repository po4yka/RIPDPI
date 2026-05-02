use smoltcp::iface::{SocketHandle, SocketSet};
use tracing::debug;

pub(super) fn remove_evicted_session_socket(socket_set: &mut SocketSet<'static>, evicted_handle: Option<SocketHandle>) {
    if let Some(evicted_handle) = evicted_handle {
        socket_set.remove(evicted_handle);
        debug!("Evicted session socket {:?} removed from socket_set", evicted_handle);
    }
}
