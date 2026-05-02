use std::io;

use crate::linux::tcp_info::{LinuxTcpInfo, TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE};

use super::sockopt::{read_tcp_timestamp, set_tcp_repair_option, set_tcp_timestamp, TcpRepairOpt};

const TCPOPT_MSS: u32 = 2;
const TCPOPT_WINDOW: u32 = 3;
const TCPOPT_SACK_PERM: u32 = 4;
const TCPOPT_TIMESTAMP: u32 = 8;

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

pub(crate) fn snapshot_tcp_repair_options(fd: libc::c_int, info: LinuxTcpInfo) -> io::Result<TcpRepairOptionsSnapshot> {
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

pub(crate) fn apply_tcp_repair_options(fd: libc::c_int, options: TcpRepairOptionsSnapshot) -> io::Result<()> {
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
