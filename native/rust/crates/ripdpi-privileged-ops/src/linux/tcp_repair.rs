use std::io;
use std::net::{SocketAddr, TcpStream};
use std::os::fd::AsRawFd;
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::linux::fd::dup2_fd;
use crate::linux::socket_options::{
    get_c_int_sockopt, get_u32_sockopt, getsockopt_raw, set_c_int_sockopt, set_u32_sockopt, setsockopt_raw,
};
use crate::linux::tcp_info::{
    read_tcp_info, tcp_has_notsent, LinuxTcpInfo, TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE,
};

pub(crate) const TCP_REPAIR: libc::c_int = 19;
pub(crate) const TCP_REPAIR_QUEUE: libc::c_int = 20;
const TCP_QUEUE_SEQ: libc::c_int = 21;
const TCP_REPAIR_OPTIONS: libc::c_int = 22;
const TCP_REPAIR_OFF_NO_WP: libc::c_int = -1;
const TCP_REPAIR_WINDOW: libc::c_int = 29;
pub(crate) const TCP_REPAIR_ON: libc::c_int = 1;
const TCP_REPAIR_OFF: libc::c_int = 0;
pub(crate) const TCP_NO_QUEUE: libc::c_int = 0;
pub(crate) const TCP_RECV_QUEUE: libc::c_int = 1;
pub(crate) const TCP_SEND_QUEUE: libc::c_int = 2;
const TCPOPT_MSS: u32 = 2;
const TCPOPT_WINDOW: u32 = 3;
const TCPOPT_SACK_PERM: u32 = 4;
const TCPOPT_TIMESTAMP: u32 = 8;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct TcpRepairWindow {
    pub(crate) snd_wl1: u32,
    pub(crate) snd_wnd: u32,
    pub(crate) max_window: u32,
    pub(crate) rcv_wnd: u32,
    pub(crate) rcv_wup: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct TcpRepairOpt {
    pub(crate) opt_code: u32,
    pub(crate) opt_val: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct StreamSocketSettings {
    pub(crate) nodelay: Option<bool>,
    pub(crate) read_timeout: Option<Option<Duration>>,
    pub(crate) write_timeout: Option<Option<Duration>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TcpTimestampSnapshot {
    pub(crate) value: u32,
    pub(crate) echo_reply: u32,
    pub(crate) usec_ts: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TcpWindowScaleSnapshot {
    pub(crate) send: u8,
    pub(crate) receive: u8,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct TcpRepairOptionsSnapshot {
    pub(crate) mss: Option<u16>,
    pub(crate) sack_permitted: bool,
    pub(crate) window_scale: Option<TcpWindowScaleSnapshot>,
    pub(crate) timestamp: Option<TcpTimestampSnapshot>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TcpRepairSnapshot {
    pub(crate) sequence_number: u32,
    pub(crate) acknowledgment_number: u32,
    pub(crate) window_size: u16,
    pub(crate) repair_window: TcpRepairWindow,
    pub(crate) options: TcpRepairOptionsSnapshot,
}

pub(crate) fn probe_tcp_repair(protect_path: Option<&str>) -> io::Result<()> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    crate::protect_socket(&socket, protect_path)?;
    let fd = socket.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    disable_tcp_repair(fd)
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

fn snapshot_tcp_repair_options(fd: libc::c_int, info: LinuxTcpInfo) -> io::Result<TcpRepairOptionsSnapshot> {
    let timestamp = if info.tcpi_options & TCPI_OPT_TIMESTAMPS != 0 {
        let value = read_tcp_timestamp(fd).map_err(|error| {
            io::Error::new(
                io::ErrorKind::Unsupported,
                format!("packet-owned TCP desync could not snapshot negotiated TCP timestamps: {error}"),
            )
        })?;
        Some(TcpTimestampSnapshot { value, echo_reply: 0, usec_ts: info.tcpi_options & TCPI_OPT_USEC_TS != 0 })
    } else {
        None
    };

    Ok(decode_tcp_repair_options(info, timestamp))
}

pub(crate) fn decode_tcp_repair_options(
    info: LinuxTcpInfo,
    timestamp: Option<TcpTimestampSnapshot>,
) -> TcpRepairOptionsSnapshot {
    let window_scale = if info.tcpi_options & TCPI_OPT_WSCALE != 0 {
        Some(TcpWindowScaleSnapshot {
            send: info.tcpi_snd_wscale_rcv_wscale & 0x0f,
            receive: info.tcpi_snd_wscale_rcv_wscale >> 4,
        })
    } else {
        None
    };

    TcpRepairOptionsSnapshot {
        mss: u16::try_from(info.tcpi_snd_mss).ok().filter(|value| *value != 0),
        sack_permitted: info.tcpi_options & TCPI_OPT_SACK != 0,
        window_scale,
        timestamp,
    }
}

pub(crate) fn build_replacement_tcp_socket(
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    snapshot: &TcpRepairSnapshot,
    protect_path: Option<&str>,
) -> io::Result<Socket> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let replacement = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    replacement.set_reuse_address(true)?;
    let _ = replacement.set_reuse_port(true);
    crate::protect_socket(&replacement, protect_path)?;

    let fd = replacement.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        replacement.bind(&SockAddr::from(source))?;

        set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
        set_tcp_queue_seq(fd, sequence_after_payload(snapshot.sequence_number, payload_len)?)?;

        set_tcp_repair_queue(fd, TCP_RECV_QUEUE)?;
        set_tcp_queue_seq(fd, snapshot.acknowledgment_number)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;

        replacement.connect(&SockAddr::from(target))?;
        apply_tcp_repair_options(fd, snapshot.options)?;
        set_tcp_repair_window(fd, snapshot.repair_window)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
        let _ = disable_tcp_repair(fd);
    }
    result.map(|_| replacement)
}

fn apply_tcp_repair_options(fd: libc::c_int, options: TcpRepairOptionsSnapshot) -> io::Result<()> {
    if let Some(mss) = options.mss {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_MSS, opt_val: u32::from(mss) })?;
    }
    if let Some(scale) = options.window_scale {
        set_tcp_repair_option(
            fd,
            TcpRepairOpt { opt_code: TCPOPT_WINDOW, opt_val: u32::from(scale.send) | (u32::from(scale.receive) << 16) },
        )?;
    }
    if options.sack_permitted {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_SACK_PERM, opt_val: 0 })?;
    }
    if let Some(timestamp) = options.timestamp {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_TIMESTAMP, opt_val: 0 })?;
        set_tcp_timestamp(fd, timestamp.value, timestamp.usec_ts)?;
    }
    Ok(())
}

pub(crate) fn swap_stream_to_replacement(
    stream: &TcpStream,
    replacement: &Socket,
    settings: StreamSocketSettings,
) -> io::Result<()> {
    let target_fd = stream.as_raw_fd();
    let replacement_fd = replacement.as_raw_fd();
    dup2_fd(replacement_fd, target_fd)?;
    apply_stream_socket_settings(stream, settings);
    Ok(())
}

pub(crate) fn capture_stream_socket_settings(stream: &TcpStream) -> StreamSocketSettings {
    StreamSocketSettings {
        nodelay: stream.nodelay().ok(),
        read_timeout: stream.read_timeout().ok(),
        write_timeout: stream.write_timeout().ok(),
    }
}

fn apply_stream_socket_settings(stream: &TcpStream, settings: StreamSocketSettings) {
    if let Some(nodelay) = settings.nodelay {
        if let Err(error) = stream.set_nodelay(nodelay) {
            tracing::debug!("failed to restore TCP_NODELAY after ipfrag2 socket handoff: {error}");
        }
    }
    if let Some(timeout) = settings.read_timeout {
        if let Err(error) = stream.set_read_timeout(timeout) {
            tracing::debug!("failed to restore read timeout after ipfrag2 socket handoff: {error}");
        }
    }
    if let Some(timeout) = settings.write_timeout {
        if let Err(error) = stream.set_write_timeout(timeout) {
            tracing::debug!("failed to restore write timeout after ipfrag2 socket handoff: {error}");
        }
    }
}

pub(crate) fn set_tcp_repair(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR, value)
}

fn set_tcp_repair_option(fd: libc::c_int, value: TcpRepairOpt) -> io::Result<()> {
    // SAFETY: `TcpRepairOpt` matches the Linux `TCP_REPAIR_OPTIONS` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_OPTIONS, &value) }
}

pub(crate) fn set_tcp_repair_queue(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR_QUEUE, value)
}

fn set_tcp_queue_seq(fd: libc::c_int, value: u32) -> io::Result<()> {
    set_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ, value)
}

fn get_tcp_queue_seq(fd: libc::c_int) -> io::Result<u32> {
    get_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ)
}

fn read_tcp_timestamp(fd: libc::c_int) -> io::Result<u32> {
    let value = get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP)?;
    u32::try_from(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative TCP timestamp"))
}

fn set_tcp_timestamp(fd: libc::c_int, value: u32, usec_ts: bool) -> io::Result<()> {
    let mut encoded = value & !1;
    if usec_ts {
        encoded |= 1;
    }
    let encoded =
        i32::try_from(encoded).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "TCP timestamp exceeds i32"))?;
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP, encoded)
}

fn get_tcp_repair_window(fd: libc::c_int) -> io::Result<TcpRepairWindow> {
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

fn pending_tcp_read_bytes(fd: libc::c_int) -> io::Result<usize> {
    let mut bytes: libc::c_int = 0;
    // SAFETY: fd is a valid TCP socket fd passed by the caller and `bytes` is a stack-allocated C integer valid for FIONREAD.
    let rc = unsafe { libc::ioctl(fd, libc::FIONREAD, &mut bytes) };
    if rc == 0 {
        usize::try_from(bytes)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative pending TCP read byte count"))
    } else {
        Err(io::Error::last_os_error())
    }
}

pub(crate) fn sequence_after_payload(sequence_number: u32, payload_len: usize) -> io::Result<u32> {
    let payload_len = u32::try_from(payload_len)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "payload too large for TCP sequence arithmetic"))?;
    sequence_number
        .checked_add(payload_len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "TCP sequence arithmetic overflow"))
}
