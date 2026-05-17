use std::io;

use crate::linux::socket_options::{
    get_c_int_sockopt, get_u32_sockopt, getsockopt_raw, set_c_int_sockopt, set_u32_sockopt, setsockopt_raw,
};

const TCP_REPAIR: libc::c_int = 19;
const TCP_REPAIR_QUEUE: libc::c_int = 20;
const TCP_QUEUE_SEQ: libc::c_int = 21;
const TCP_REPAIR_OPTIONS: libc::c_int = 22;
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

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct TcpRepairOpt {
    pub(crate) opt_code: u32,
    pub(crate) opt_val: u32,
}

// Compile-time ABI guard: Linux `tcp_repair_opt` is two `u32` fields
// (8 bytes, align 4).
const _: () = {
    assert!(std::mem::size_of::<TcpRepairOpt>() == 8);
    assert!(std::mem::align_of::<TcpRepairOpt>() == 4);
};

pub(crate) fn set_tcp_repair(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR, value)
}

pub(crate) fn set_tcp_repair_option(fd: libc::c_int, value: TcpRepairOpt) -> io::Result<()> {
    // SAFETY: `TcpRepairOpt` matches the Linux `TCP_REPAIR_OPTIONS` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_OPTIONS, &value) }
}

pub(crate) fn set_tcp_repair_queue(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR_QUEUE, value)
}

pub(crate) fn set_tcp_queue_seq(fd: libc::c_int, value: u32) -> io::Result<()> {
    set_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ, value)
}

pub(crate) fn get_tcp_queue_seq(fd: libc::c_int) -> io::Result<u32> {
    get_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ)
}

pub(crate) fn read_tcp_timestamp(fd: libc::c_int) -> io::Result<u32> {
    let value = get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP)?;
    u32::try_from(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative TCP timestamp"))
}

pub(crate) fn set_tcp_timestamp(fd: libc::c_int, value: u32, usec_ts: bool) -> io::Result<()> {
    let mut encoded = value & !1;
    if usec_ts {
        encoded |= 1;
    }
    let encoded =
        i32::try_from(encoded).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "TCP timestamp exceeds i32"))?;
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP, encoded)
}

pub(crate) fn get_tcp_repair_window(fd: libc::c_int) -> io::Result<TcpRepairWindow> {
    // SAFETY: `TcpRepairWindow` matches the Linux `TCP_REPAIR_WINDOW` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    let (value, _): (TcpRepairWindow, _) = unsafe { getsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_WINDOW) }?;
    Ok(value)
}

pub(crate) fn set_tcp_repair_window(fd: libc::c_int, value: TcpRepairWindow) -> io::Result<()> {
    // SAFETY: `TcpRepairWindow` matches the Linux `TCP_REPAIR_WINDOW` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_WINDOW, &value) }
}

pub(crate) fn disable_tcp_repair(fd: libc::c_int) -> io::Result<()> {
    set_tcp_repair(fd, TCP_REPAIR_OFF_NO_WP).or_else(|_| set_tcp_repair(fd, TCP_REPAIR_OFF))
}
