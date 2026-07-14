use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;

pub(super) fn remove_tcp_socket(socket_set: &mut SocketSet<'static>, handle: SocketHandle) {
    let tcp = socket_set.get_mut::<TcpSocket>(handle);
    if tcp.is_active() {
        tcp.close();
    }
    socket_set.remove(handle);
}
