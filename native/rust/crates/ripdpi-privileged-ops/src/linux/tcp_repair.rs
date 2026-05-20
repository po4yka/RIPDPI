//! Linux TCP_REPAIR helpers for socket state capture and replacement.
//!
//! Privileged boundaries include TCP_REPAIR socket options, queue sequence reads,
//! and fd replacement via duplicated sockets.

mod fionread;
mod handoff;
mod options;
mod replacement;
mod settings;
mod snapshot;
mod sockopt;

use std::io;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::os::fd::AsRawFd;

pub(crate) use handoff::swap_stream_to_replacement;
pub(crate) use options::TcpTimestampSnapshot;
#[cfg(test)]
pub(crate) use options::{decode_tcp_repair_options, TcpRepairOptionsSnapshot, TcpWindowScaleSnapshot};
pub(crate) use replacement::{build_replacement_tcp_socket, sequence_after_payload};
pub(crate) use settings::capture_stream_socket_settings;
pub(crate) use snapshot::{snapshot_tcp_repair_state, TcpRepairSnapshot};
#[cfg(test)]
pub(crate) use sockopt::TcpRepairWindow;
pub(crate) use sockopt::{disable_tcp_repair, set_tcp_repair, set_tcp_repair_queue, TCP_NO_QUEUE, TCP_REPAIR_ON};

pub(crate) fn probe_tcp_repair(protect_path: Option<&str>) -> io::Result<()> {
    let (client, _server) = connected_loopback_pair()?;
    let fd = client.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        let snapshot = snapshot_tcp_repair_state(fd)?;
        let _replacement =
            build_replacement_tcp_socket(client.local_addr()?, client.peer_addr()?, 0, &snapshot, protect_path)?;
        Ok(())
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
