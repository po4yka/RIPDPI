use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use ripdpi_proxy_runtime_adapter::platform as runtime_platform;

use super::conversion::to_runtime_flags;

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_seqovl_tcp(
        stream,
        real_chunk,
        fake_prefix,
        default_ttl,
        protect_path,
        md5sig,
        to_runtime_flags(flags),
        ip_id_mode,
    )
}
