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
use std::os::fd::AsRawFd;

use socket2::{Domain, Protocol, Socket, Type};

pub(crate) use handoff::swap_stream_to_replacement;
pub(crate) use options::{
    decode_tcp_repair_options, TcpRepairOptionsSnapshot, TcpTimestampSnapshot, TcpWindowScaleSnapshot,
};
pub(crate) use replacement::{build_replacement_tcp_socket, sequence_after_payload};
pub(crate) use settings::{capture_stream_socket_settings, StreamSocketSettings};
pub(crate) use snapshot::{snapshot_tcp_repair_state, TcpRepairSnapshot};
pub(crate) use sockopt::{
    disable_tcp_repair, set_tcp_repair, set_tcp_repair_queue, set_tcp_repair_window, TcpRepairWindow, TCP_NO_QUEUE,
    TCP_REPAIR_ON,
};

pub(crate) fn probe_tcp_repair(protect_path: Option<&str>) -> io::Result<()> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    crate::protect_socket(&socket, protect_path)?;
    let fd = socket.as_raw_fd();

    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    disable_tcp_repair(fd)
}
