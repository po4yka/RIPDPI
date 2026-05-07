use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{
    TcpFlagOverrides as DesyncTcpFlagOverrides, TcpFragmentSender, TcpPayloadSegment as DesyncTcpPayloadSegment,
};

use ripdpi_proxy_runtime_adapter::platform as runtime_platform;

use super::conversion::to_runtime_flags;
use super::RuntimeTcpDesyncPlatform;

impl TcpFragmentSender for RuntimeTcpDesyncPlatform {
    #[allow(clippy::too_many_arguments)]
    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: ripdpi_proxy_runtime_adapter::ip_fragmentation::Ipv6ExtHeaders,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            flags,
            ip_id_mode,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[DesyncTcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        super::multi_disorder::send_multi_disorder_tcp(
            stream,
            payload,
            segments,
            default_ttl,
            protect_path,
            inter_segment_delay_ms,
            md5sig,
            original_flags,
            ip_id_mode,
        )
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_proxy_runtime_adapter::ip_fragmentation::Ipv6ExtHeaders,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_ip_fragmented_tcp(
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
