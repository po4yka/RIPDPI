use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;

pub(super) fn remove_tcp_socket(socket_set: &mut SocketSet<'static>, handle: SocketHandle) {
    if socket_set.iter().any(|(socket_handle, _)| socket_handle == handle) {
        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        if tcp.is_active() {
            tcp.close();
        }
        socket_set.remove(handle);
    }
}
