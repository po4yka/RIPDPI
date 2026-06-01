//! Root-helper dispatch half of the fake-send privileged operations.
//!
//! Each function here forwards one fake-packet operation to the privileged
//! `ripdpi-root-helper` over `with_root_helper`. The `Option` return type is
//! the **non-root fallback signal**: `Some(result)` means a helper was
//! registered and ran the operation (privileged path); `None` means no helper
//! is registered, and the caller in the sibling `fake_send::*` modules must
//! fall through to the local `ripdpi-privileged-ops` path. A `None` here is
//! never an error — it is the rooted-vs-non-root branch.
//!
//! ## Unsafe surface
//!
//! One `unsafe` call, in `swap_stream_replacement_fd`: when the root helper
//! returns a freshly-created replacement descriptor it is installed over the
//! caller's stream fd via `ripdpi_privileged_ops::swap_replacement_fd`. The
//! replacement fd is retained by no other path and `stream` only borrows its
//! descriptor for the call — see the per-call `// SAFETY:` note.

use std::io;
use std::net::TcpStream;
use std::os::fd::{AsRawFd, RawFd};

use crate::{FakeTcpOptions, OrderedTcpSegment, TcpFlagOverrides, TcpStageWait, root_helper};

pub(crate) fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> Option<io::Result<()>> {
    root_helper::with_root_helper(|h| h.send_fake_rst(stream.as_raw_fd(), default_ttl, flags, ipv4_identification))
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: &FakeTcpOptions<'_>,
    wait: TcpStageWait,
) -> Option<io::Result<()>> {
    root_helper::with_root_helper(|h| {
        let res =
            h.send_fake_tcp(stream.as_raw_fd(), original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)?;
        swap_stream_replacement_fd(stream, res)?;
        Ok(())
    })
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ipv4_identifications: &[u16],
    wait: TcpStageWait,
) -> Option<io::Result<()>> {
    root_helper::with_root_helper(|h| {
        let res = h.send_ordered_tcp_segments(
            stream.as_raw_fd(),
            segments,
            original_payload_len,
            default_ttl,
            md5sig,
            timestamp_delta_ticks,
            ipv4_identifications,
            wait,
        )?;
        swap_stream_replacement_fd(stream, res)?;
        Ok(())
    })
}

pub(crate) fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> Option<io::Result<()>> {
    root_helper::with_root_helper(|h| {
        let res =
            h.send_flagged_tcp_payload(stream.as_raw_fd(), payload, default_ttl, md5sig, flags, ipv4_identification)?;
        swap_stream_replacement_fd(stream, res)?;
        Ok(())
    })
}

pub(crate) fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> Option<io::Result<()>> {
    root_helper::with_root_helper(|h| {
        let res = h.send_seqovl_tcp(
            stream.as_raw_fd(),
            real_chunk,
            fake_prefix,
            default_ttl,
            md5sig,
            flags,
            ipv4_identification,
        )?;
        swap_stream_replacement_fd(stream, res)?;
        Ok(())
    })
}

fn swap_stream_replacement_fd(stream: &TcpStream, replacement_fd: Option<RawFd>) -> io::Result<()> {
    if let Some(replacement_fd) = replacement_fd {
        // SAFETY: `replacement_fd` was just returned by the privileged-ops
        // root helper as a freshly created socket descriptor; no other code
        // path retains it. `stream` borrows its descriptor for the duration
        // of this call.
        unsafe { ripdpi_privileged_ops::swap_replacement_fd(stream.as_raw_fd(), replacement_fd) }?;
    }
    Ok(())
}
