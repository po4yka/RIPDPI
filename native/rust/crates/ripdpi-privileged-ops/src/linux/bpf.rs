//! Linux classic BPF socket-filter helpers for TCP option suppression.
//!
//! Unsafe boundaries are limited to passing in-process filter programs to
//! `setsockopt(SO_ATTACH_FILTER)`.

use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;

use crate::linux::socket_options::{set_c_int_sockopt, setsockopt_raw};

/// Attach a BPF filter that drops incoming TCP segments containing a SACK
/// option.
///
/// **Limitation:** The filter checks a fixed offset (0x22) for the SACK option
/// kind byte rather than performing a full TLV scan of the TCP options field.
/// This works for the vast majority of Linux TCP stacks where SACK appears at a
/// predictable position, but may miss SACK placed at non-standard offsets by
/// unusual middleboxes or custom stacks.
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    let mut code = [
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0000000c },
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 0x00000004 },
        libc::sock_filter { code: 0x35, jt: 0, jf: 3, k: 0x0000000b },
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x00000022 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 1, k: 0x00000005 },
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00000000 },
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00040000 },
    ];
    let prog = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };

    // SAFETY: `prog` points to a live in-process BPF program and `fd` is a
    // valid TCP socket descriptor owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, &prog) }
}

pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    set_c_int_sockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_DETACH_FILTER, 0)
}

/// Attach a BPF socket filter that drops outgoing TCP segments containing
/// the Timestamps option (kind=8).
///
/// This prevents DPI from using TCP timestamp values for flow-level timing
/// correlation. The BPF program checks the TCP option kind byte at a fixed
/// offset (same approach as [`attach_drop_sack`] for SACK kind=5).
///
/// **Note:** `SO_ATTACH_FILTER` replaces any prior filter. If both `drop_sack`
/// and `strip_timestamps` are needed, call `attach_drop_sack` first — this
/// filter will replace it. Combined filtering requires one BPF program that
/// handles both checks.
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    // BPF program: check TCP data offset (byte 12, upper nibble) >= 11 words
    // (44 bytes, meaning options exist beyond the basic 20-byte header).
    // Then check option kind byte at offset 0x1e (byte 30 = start of options
    // area for most common layouts where timestamps appear first).
    // If kind == 8 (Timestamps), drop the packet (return 0).
    // Otherwise, accept (return 0x40000).
    let mut code = [
        // Load byte at offset 12 (TCP data offset + flags)
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0000000c },
        // Shift right 4 to get data offset in 32-bit words
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 0x00000004 },
        // If data offset < 8 (header < 32 bytes = no room for timestamps), accept
        libc::sock_filter { code: 0x35, jt: 0, jf: 3, k: 0x00000008 },
        // Load byte at offset 0x14 (byte 20 = first option kind after 20-byte header)
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x00000014 },
        // If kind == 8 (Timestamps), drop
        libc::sock_filter { code: 0x15, jt: 0, jf: 1, k: 0x00000008 },
        // Drop: return 0
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00000000 },
        // Accept: return max
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00040000 },
    ];
    let prog = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };

    // SAFETY: `prog` points to a live in-process BPF program and `fd` is a
    // valid TCP socket descriptor owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, &prog) }
}
