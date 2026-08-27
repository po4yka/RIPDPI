use std::collections::HashMap;

use crate::io_loop::packet::TcpFlowKey;
use crate::io_loop::tcp_accept::PendingListener;

pub(super) const TCP_ADMISSION_WORK_BUDGET: usize = 64;

pub(super) fn pending_listener_batch<'a>(
    pending_listens: &'a HashMap<TcpFlowKey, PendingListener>,
    admission_cursor: &mut usize,
    budget: usize,
) -> Vec<&'a PendingListener> {
    let pending_count = pending_listens.len();
    if pending_count == 0 {
        *admission_cursor = 0;
        return Vec::new();
    }
    let start = *admission_cursor % pending_count;
    let handles: Vec<_> = pending_listens
        .values()
        .skip(start)
        .chain(pending_listens.values().take(start))
        .take(budget.min(pending_count))
        .collect();
    *admission_cursor = (start + handles.len()) % pending_count;
    handles
}

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::net::{Ipv4Addr, SocketAddr};

    use smoltcp::iface::SocketSet;
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};

    use super::*;

    #[test]
    fn pending_admission_batches_are_bounded_and_rotate_across_all_handles() {
        let mut socket_set = SocketSet::new(vec![]);
        let mut pending = HashMap::new();
        for index in 0..130u16 {
            let socket = TcpSocket::new(tcp::SocketBuffer::new(vec![0; 1]), tcp::SocketBuffer::new(vec![0; 1]));
            let handle = socket_set.add(socket);
            let key = TcpFlowKey {
                src: SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 10_000 + index),
                dst: SocketAddr::new(Ipv4Addr::new(203, 0, 113, 1).into(), 443),
            };
            pending.insert(key, PendingListener::new(handle, key));
        }
        let mut cursor = 0;
        let mut seen = HashSet::new();
        for _ in 0..3 {
            let batch = pending_listener_batch(&pending, &mut cursor, TCP_ADMISSION_WORK_BUDGET);
            assert_eq!(batch.len(), TCP_ADMISSION_WORK_BUDGET);
            seen.extend(batch.into_iter().map(|listener| listener.handle));
        }
        assert_eq!(seen.len(), pending.len(), "rotating batches must eventually visit every pending socket");
    }
}
