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
/// `SO_ATTACH_FILTER` sees received segments only, and it drops the whole
/// segment (its ACK and payload too), not just the option. Outbound segments
/// are not changed.
///
/// Only the NOP,NOP,Timestamps,NOP,NOP,SACK layout is recognized. The
/// SACK blocks must fill the remaining TCP header. Other layouts pass through.
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    let mut code = drop_sack_program();
    let prog = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };

    // SAFETY: `prog` points to a live in-process BPF program and `fd` is a
    // valid TCP socket descriptor owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, &prog) }
}

fn drop_sack_program() -> [libc::sock_filter; 30] {
    [
        // Check the input length before loading any fixed option offsets.
        libc::sock_filter { code: 0x80, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x35, jt: 0, jf: 27, k: 0x2c },
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0c },
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 4 },
        libc::sock_filter { code: 0x35, jt: 0, jf: 24, k: 11 },
        // These preceding TLVs prove that byte 34 is an option kind.
        libc::sock_filter { code: 0x20, jt: 0, jf: 0, k: 0x14 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 22, k: 0x0101080a },
        libc::sock_filter { code: 0x28, jt: 0, jf: 0, k: 0x20 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 20, k: 0x0101 },
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x22 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 18, k: 5 },
        // A SACK option has length 2 + 8N, with at least one block.
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x23 },
        libc::sock_filter { code: 0x35, jt: 0, jf: 16, k: 10 },
        libc::sock_filter { code: 0x02, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x54, jt: 0, jf: 0, k: 7 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 13, k: 2 },
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0c },
        libc::sock_filter { code: 0x54, jt: 0, jf: 0, k: 0xf0 },
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 2 },
        libc::sock_filter { code: 0x02, jt: 0, jf: 0, k: 1 },
        libc::sock_filter { code: 0x07, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x80, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x3d, jt: 0, jf: 6, k: 0 },
        // Require the SACK blocks to end at the complete TCP header boundary.
        libc::sock_filter { code: 0x60, jt: 0, jf: 0, k: 1 },
        libc::sock_filter { code: 0x14, jt: 0, jf: 0, k: 34 },
        libc::sock_filter { code: 0x07, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x60, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x1d, jt: 0, jf: 1, k: 0 },
        libc::sock_filter { code: 0x06, jt: 0, jf: 0, k: 0 },
        libc::sock_filter { code: 0x06, jt: 0, jf: 0, k: 0x00040000 },
    ]
}

pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    set_c_int_sockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_DETACH_FILTER, 0)
}

/// Attach a BPF socket filter that drops incoming TCP segments whose first
/// option is Timestamps (kind=8).
///
/// `SO_ATTACH_FILTER` sees received segments only, so this does not remove
/// the option from outbound segments, and the SYN has already carried it.
/// The program checks only the first option byte; Linux, BSD and Windows
/// peers put NOP padding before Timestamps, so it rarely matches.
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

#[cfg(test)]
mod tests;
