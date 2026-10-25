use std::io;

use crate::linux::tcp_info::{LinuxTcpInfo, TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE};

use super::sockopt::read_tcp_timestamp;

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
