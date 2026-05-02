use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use super::options::apply_tcp_repair_options;
use super::snapshot::TcpRepairSnapshot;
use super::sockopt::{
    disable_tcp_repair, set_tcp_queue_seq, set_tcp_repair, set_tcp_repair_queue, set_tcp_repair_window, TCP_NO_QUEUE,
    TCP_RECV_QUEUE, TCP_REPAIR_ON, TCP_SEND_QUEUE,
};

pub(crate) fn build_replacement_tcp_socket(
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    snapshot: &TcpRepairSnapshot,
    protect_path: Option<&str>,
) -> io::Result<Socket> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let replacement = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    replacement.set_reuse_address(true)?;
    let _ = replacement.set_reuse_port(true);
    crate::protect_socket(&replacement, protect_path)?;

    let fd = replacement.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        replacement.bind(&SockAddr::from(source))?;

        set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
        set_tcp_queue_seq(fd, sequence_after_payload(snapshot.sequence_number, payload_len)?)?;

        set_tcp_repair_queue(fd, TCP_RECV_QUEUE)?;
        set_tcp_queue_seq(fd, snapshot.acknowledgment_number)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;

        replacement.connect(&SockAddr::from(target))?;
        apply_tcp_repair_options(fd, snapshot.options)?;
        set_tcp_repair_window(fd, snapshot.repair_window)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
        let _ = disable_tcp_repair(fd);
    }
    result.map(|_| replacement)
}

pub(crate) fn sequence_after_payload(sequence_number: u32, payload_len: usize) -> io::Result<u32> {
    let payload_len = u32::try_from(payload_len)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "payload too large for TCP sequence arithmetic"))?;
    sequence_number
        .checked_add(payload_len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "TCP sequence arithmetic overflow"))
}
