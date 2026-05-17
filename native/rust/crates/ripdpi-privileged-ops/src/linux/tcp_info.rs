//! Linux `TCP_INFO` accessors and TCP-stage wait helpers.
//!
//! The syscall boundary is typed `getsockopt(TCP_INFO)` reads of kernel tcp_info
//! layouts used to decide when packet injection is safe.

use std::io;
use std::mem::size_of;
use std::net::{SocketAddr, TcpStream};
use std::os::fd::AsRawFd;
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_desync::TcpSegmentHint;

use crate::linux::socket_options::getsockopt_raw;
use crate::linux::tcp_repair::{
    disable_tcp_repair, set_tcp_repair, set_tcp_repair_queue, snapshot_tcp_repair_state, TCP_NO_QUEUE, TCP_REPAIR_ON,
};
use crate::TcpActivationState;

pub(crate) const TCP_ESTABLISHED: u8 = 1;
pub(crate) const TCPI_OPT_TIMESTAMPS: u8 = 1;
pub(crate) const TCPI_OPT_SACK: u8 = 2;
pub(crate) const TCPI_OPT_WSCALE: u8 = 4;
pub(crate) const TCPI_OPT_USEC_TS: u8 = 64;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct LinuxTcpInfo {
    pub(crate) tcpi_state: u8,
    pub(crate) tcpi_ca_state: u8,
    pub(crate) tcpi_retransmits: u8,
    pub(crate) tcpi_probes: u8,
    pub(crate) tcpi_backoff: u8,
    pub(crate) tcpi_options: u8,
    pub(crate) tcpi_snd_wscale_rcv_wscale: u8,
    pub(crate) tcpi_delivery_rate_app_limited_fastopen_client_fail: u8,
    pub(crate) tcpi_rto: u32,
    pub(crate) tcpi_ato: u32,
    pub(crate) tcpi_snd_mss: u32,
    pub(crate) tcpi_rcv_mss: u32,
    pub(crate) tcpi_unacked: u32,
    pub(crate) tcpi_sacked: u32,
    pub(crate) tcpi_lost: u32,
    pub(crate) tcpi_retrans: u32,
    pub(crate) tcpi_fackets: u32,
    pub(crate) tcpi_last_data_sent: u32,
    pub(crate) tcpi_last_ack_sent: u32,
    pub(crate) tcpi_last_data_recv: u32,
    pub(crate) tcpi_last_ack_recv: u32,
    pub(crate) tcpi_pmtu: u32,
    pub(crate) tcpi_rcv_ssthresh: u32,
    pub(crate) tcpi_rtt: u32,
    pub(crate) tcpi_rttvar: u32,
    pub(crate) tcpi_snd_ssthresh: u32,
    pub(crate) tcpi_snd_cwnd: u32,
    pub(crate) tcpi_advmss: u32,
    pub(crate) tcpi_reordering: u32,
    pub(crate) tcpi_rcv_rtt: u32,
    pub(crate) tcpi_rcv_space: u32,
    pub(crate) tcpi_total_retrans: u32,
    pub(crate) tcpi_pacing_rate: u64,
    pub(crate) tcpi_max_pacing_rate: u64,
    pub(crate) tcpi_bytes_acked: u64,
    pub(crate) tcpi_bytes_received: u64,
    pub(crate) tcpi_segs_out: u32,
    pub(crate) tcpi_segs_in: u32,
    pub(crate) tcpi_notsent_bytes: u32,
}

// Compile-time ABI guard for issue #24: `LinuxTcpInfo` is the prefix
// of the kernel `tcp_info` struct that this crate consumes via
// `getsockopt(TCP_INFO)`. `read_tcp_info` checks `len >=
// size_of::<LinuxTcpInfo>()` before accepting the result, so the
// declared size IS the contract — any field reorder/insert/delete
// must be intentional. The field bytes total to 148 (8 u8 + 24 u32
// + 4 u64 + 3 u32 = 8 + 96 + 32 + 12); struct alignment depends on
// u64 alignment, which is 8 on aarch64/x86_64/armv7 and 4 on i686
// Linux. We assert a lower bound and an alignment relation rather
// than a strict equality so the assert holds across all four
// Android targets in this workspace.
const _: () = {
    assert!(std::mem::size_of::<LinuxTcpInfo>() >= 148);
    assert!(std::mem::align_of::<LinuxTcpInfo>() >= std::mem::align_of::<u32>());
};

pub(crate) fn tcp_has_notsent(fd: libc::c_int) -> io::Result<bool> {
    let Some(info) = read_tcp_info(fd)? else {
        return Ok(false);
    };
    if info.tcpi_state != TCP_ESTABLISHED {
        return Ok(false);
    }
    Ok(info.tcpi_notsent_bytes != 0)
}

pub(crate) fn read_tcp_info(fd: libc::c_int) -> io::Result<Option<LinuxTcpInfo>> {
    // SAFETY: `fd` is a live TCP socket; `LinuxTcpInfo` is a `#[repr(C)]`
    // prefix of the kernel `tcp_info` struct.
    let (info, len) = unsafe { getsockopt_raw::<LinuxTcpInfo>(fd, libc::IPPROTO_TCP, libc::TCP_INFO) }?;
    if (len as usize) < size_of::<LinuxTcpInfo>() {
        return Ok(None);
    }
    Ok(Some(info))
}

pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    // Combined IP + TCP header overhead (IPv4: 20+20=40, IPv6: 40+20=60).
    // Named `ip_header_overhead` for historical reasons; the value includes
    // the minimum TCP header as well.
    let ip_header_overhead = match stream.peer_addr()? {
        SocketAddr::V4(_) => 40,
        SocketAddr::V6(_) => 60,
    };
    Ok(Some(TcpSegmentHint {
        snd_mss: (info.tcpi_snd_mss != 0).then_some(info.tcpi_snd_mss as i64),
        advmss: (info.tcpi_advmss != 0).then_some(info.tcpi_advmss as i64),
        pmtu: (info.tcpi_pmtu != 0).then_some(info.tcpi_pmtu as i64),
        ip_header_overhead,
    }))
}

pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    let fd = stream.as_raw_fd();
    let snapshot = set_tcp_repair(fd, TCP_REPAIR_ON)
        .and_then(|_| {
            let snapshot = snapshot_tcp_repair_state(fd);
            let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
            let _ = disable_tcp_repair(fd);
            snapshot
        })
        .ok();

    Ok(Some(TcpActivationState {
        has_timestamp: Some(
            snapshot.as_ref().is_some_and(|value| value.options.timestamp.is_some())
                || (info.tcpi_options & TCPI_OPT_TIMESTAMPS != 0),
        ),
        window_size: snapshot.map(|value| i64::from(value.window_size)),
        mss: snapshot
            .and_then(|value| value.options.mss.map(i64::from))
            .or_else(|| (info.tcpi_snd_mss != 0).then_some(info.tcpi_snd_mss as i64)),
    }))
}

pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    if info.tcpi_state != TCP_ESTABLISHED {
        return Ok(None);
    }
    Ok(Some(u64::from(info.tcpi_rtt / 1_000)))
}

pub fn tcp_total_retransmissions<T: AsRawFd>(socket: &T) -> io::Result<Option<u32>> {
    let Some(info) = read_tcp_info(socket.as_raw_fd())? else {
        return Ok(None);
    };
    tcp_total_retransmissions_from_info(&info)
}

pub(crate) fn tcp_total_retransmissions_from_info(info: &LinuxTcpInfo) -> io::Result<Option<u32>> {
    if info.tcpi_state == 0 {
        return Ok(None);
    }
    Ok(Some(info.tcpi_total_retrans.max(u32::from(info.tcpi_retransmits))))
}

pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    wait_tcp_stage_fd(stream.as_raw_fd(), wait_send, await_interval)
}

pub(crate) fn wait_tcp_stage_fd(fd: libc::c_int, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    let sleep_for = if await_interval.is_zero() { Duration::from_millis(1) } else { await_interval };
    if wait_send {
        thread::sleep(sleep_for);
        if !tcp_has_notsent(fd)? {
            return Ok(());
        }
    } else if !tcp_has_notsent(fd)? {
        return Ok(());
    }

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if Instant::now() >= deadline {
            if wait_send {
                return Ok(());
            }
            return Err(io::Error::new(io::ErrorKind::TimedOut, "timed out waiting for tcp send queue to drain"));
        }
        thread::sleep(sleep_for);
        if !tcp_has_notsent(fd)? {
            return Ok(());
        }
    }
}
