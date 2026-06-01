use std::io;

use crate::linux::tcp_info::{read_tcp_info, tcp_has_notsent};

use super::fionread::pending_tcp_read_bytes;
use super::options::{TcpRepairOptionsSnapshot, snapshot_tcp_repair_options};
use super::sockopt::{
    TCP_NO_QUEUE, TCP_RECV_QUEUE, TCP_SEND_QUEUE, TcpRepairWindow, get_tcp_queue_seq, get_tcp_repair_window,
    set_tcp_repair_queue,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TcpRepairSnapshot {
    pub(crate) sequence_number: u32,
    pub(crate) acknowledgment_number: u32,
    pub(crate) window_size: u16,
    pub(crate) repair_window: TcpRepairWindow,
    pub(crate) options: TcpRepairOptionsSnapshot,
}

pub(crate) fn snapshot_tcp_repair_state(fd: libc::c_int) -> io::Result<TcpRepairSnapshot> {
    if pending_tcp_read_bytes(fd)? != 0 {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "packet-owned TCP desync requires an empty inbound queue before raw injection",
        ));
    }
    if tcp_has_notsent(fd)? {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "packet-owned TCP desync requires an empty TCP send queue before raw injection",
        ));
    }

    let info = read_tcp_info(fd)?.ok_or_else(|| {
        io::Error::new(io::ErrorKind::Unsupported, "packet-owned TCP desync requires TCP_INFO support")
    })?;

    set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
    let sequence_number = get_tcp_queue_seq(fd)?;
    let repair_window = get_tcp_repair_window(fd)?;

    set_tcp_repair_queue(fd, TCP_RECV_QUEUE)?;
    let acknowledgment_number = get_tcp_queue_seq(fd)?;
    set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;

    let options = snapshot_tcp_repair_options(fd, info)?;
    Ok(TcpRepairSnapshot {
        sequence_number,
        acknowledgment_number,
        window_size: repair_window.rcv_wnd.min(u32::from(u16::MAX)) as u16,
        repair_window,
        options,
    })
}
