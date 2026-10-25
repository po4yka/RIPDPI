//! Linux TCP_REPAIR helpers for socket state capture and send-queue handoff.
//!
//! Privileged boundaries include TCP_REPAIR socket options, queue sequence reads,
//! and repair-mode writes that mark crafted payloads as sent.

mod fionread;
mod options;
mod send_queue;
mod snapshot;
mod sockopt;

use std::io;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::os::fd::AsRawFd;

pub(crate) use options::TcpTimestampSnapshot;
#[cfg(test)]
pub(crate) use options::{TcpRepairOptionsSnapshot, TcpWindowScaleSnapshot, decode_tcp_repair_options};
pub(crate) use send_queue::{queue_payload_as_sent, sequence_after_payload};
pub(crate) use snapshot::{TcpRepairSnapshot, snapshot_tcp_repair_state};
pub(crate) use sockopt::{TCP_NO_QUEUE, TCP_REPAIR_ON, disable_tcp_repair, set_tcp_repair, set_tcp_repair_queue};
#[cfg(test)]
pub(crate) use sockopt::{TcpRepairWindow, read_tcp_timestamp};

pub(crate) fn probe_tcp_repair(protect_path: Option<&str>) -> io::Result<()> {
    let (client, _server) = connected_loopback_pair()?;
    let fd = client.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        crate::protect_socket(&client, protect_path)?;
        snapshot_tcp_repair_state(fd).map(|_| ())
    })();

    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    result
}

fn connected_loopback_pair() -> io::Result<(TcpStream, TcpStream)> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
    let addr = listener.local_addr()?;
    let client = TcpStream::connect(addr)?;
    let (server, _) = listener.accept()?;
    Ok((client, server))
}
