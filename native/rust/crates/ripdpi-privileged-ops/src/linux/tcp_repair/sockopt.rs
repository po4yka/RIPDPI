use std::io;

use crate::linux::socket_options::{get_c_int_sockopt, get_u32_sockopt, getsockopt_raw, set_c_int_sockopt};

const TCP_REPAIR: libc::c_int = 19;
const TCP_REPAIR_QUEUE: libc::c_int = 20;
const TCP_QUEUE_SEQ: libc::c_int = 21;
const TCP_REPAIR_OFF_NO_WP: libc::c_int = -1;
const TCP_REPAIR_WINDOW: libc::c_int = 29;
const TCP_REPAIR_OFF: libc::c_int = 0;
pub(crate) const TCP_REPAIR_ON: libc::c_int = 1;
pub(crate) const TCP_NO_QUEUE: libc::c_int = 0;
pub(crate) const TCP_RECV_QUEUE: libc::c_int = 1;
pub(crate) const TCP_SEND_QUEUE: libc::c_int = 2;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct TcpRepairWindow {
    pub(crate) snd_wl1: u32,
    pub(crate) snd_wnd: u32,
    pub(crate) max_window: u32,
    pub(crate) rcv_wnd: u32,
    pub(crate) rcv_wup: u32,
}

// Compile-time ABI guard for issue #24: the Linux kernel
// `tcp_repair_window` is 5 contiguous `u32` fields (20 bytes, align 4).
// `#[repr(C)]` should produce that exact layout — any reordering
// (e.g. due to a future field addition) fails to compile here, before
// `setsockopt(TCP_REPAIR_WINDOW)` could ever see a mismatched buffer.
const _: () = {
    assert!(std::mem::size_of::<TcpRepairWindow>() == 20);
    assert!(std::mem::align_of::<TcpRepairWindow>() == 4);
};

pub(crate) fn set_tcp_repair(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR, value)
}

pub(crate) fn set_tcp_repair_queue(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR_QUEUE, value)
}

pub(crate) fn get_tcp_queue_seq(fd: libc::c_int) -> io::Result<u32> {
    get_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ)
}

pub(crate) fn read_tcp_timestamp(fd: libc::c_int) -> io::Result<u32> {
    // The kernel returns the u32 TSval in an int, so values >= 2^31 come back negative.
    Ok(get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP)?.cast_unsigned())
}

pub(crate) fn get_tcp_repair_window(fd: libc::c_int) -> io::Result<TcpRepairWindow> {
    // SAFETY: `TcpRepairWindow` matches the Linux `TCP_REPAIR_WINDOW` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    let (value, _): (TcpRepairWindow, _) = unsafe { getsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_WINDOW) }?;
    Ok(value)
}

pub(crate) fn disable_tcp_repair(fd: libc::c_int) -> io::Result<()> {
    set_tcp_repair(fd, TCP_REPAIR_OFF_NO_WP).or_else(|_| set_tcp_repair(fd, TCP_REPAIR_OFF))
}
