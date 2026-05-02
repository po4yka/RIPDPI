use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{
    OrderedTcpSegment as DesyncOrderedTcpSegment, TcpFlagOverrides as DesyncTcpFlagOverrides, TcpPayloadSender,
    TcpStageWait,
};

use super::{flagged_payload, ordered_segments, seq_overlap, RuntimeTcpDesyncPlatform};

impl TcpPayloadSender for RuntimeTcpDesyncPlatform {
    #[allow(clippy::too_many_arguments)]
    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[DesyncOrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()> {
        ordered_segments::send_ordered_tcp_segments(
            stream,
            segments,
            original_payload_len,
            default_ttl,
            protect_path,
            md5sig,
            timestamp_delta_ticks,
            ip_id_mode,
            wait,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn send_flagged_tcp_payload(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        flagged_payload::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    }

    #[allow(clippy::too_many_arguments)]
    fn send_seqovl_tcp(
        &self,
        stream: &TcpStream,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        seq_overlap::send_seqovl_tcp(
            stream,
            real_chunk,
            fake_prefix,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
        )
    }
}
