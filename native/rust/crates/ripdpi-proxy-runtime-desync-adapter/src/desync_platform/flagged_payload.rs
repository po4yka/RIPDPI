use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use crate::platform as runtime_platform;

use super::conversion::to_runtime_flags;

#[allow(clippy::too_many_arguments)]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_flagged_tcp_payload(
        stream,
        payload,
        default_ttl,
        protect_path,
        md5sig,
        to_runtime_flags(flags),
        ip_id_mode,
    )
}
