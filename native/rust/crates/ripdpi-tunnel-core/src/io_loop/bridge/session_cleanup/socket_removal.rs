use smoltcp::iface::{SocketHandle, SocketSet};

use crate::ActiveSessions;

/// A session that ends while its socket is still active ended abnormally (a bridge error, a
/// denial, or loop shutdown), so the app gets a RST at the next poll instead of a silent drop.
pub(super) fn remove_tcp_socket(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    handle: SocketHandle,
) {
    sessions.retire_socket(socket_set, handle);
}
