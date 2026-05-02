use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::TcpFlagOverrides as DesyncTcpFlagOverrides;

use ripdpi_runtime_platform as runtime_platform;

use super::conversion::to_runtime_flags;

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        to_runtime_flags(flags),
        ip_id_mode,
    )
}
