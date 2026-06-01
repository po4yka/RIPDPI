use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{
    FakeTcpOptions as DesyncFakeTcpOptions, TcpFakeSender, TcpFlagOverrides as DesyncTcpFlagOverrides, TcpStageWait,
};

use crate::platform as runtime_platform;

use super::RuntimeTcpDesyncPlatform;
use super::conversion::to_runtime_flags;

impl TcpFakeSender for RuntimeTcpDesyncPlatform {
    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: DesyncTcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        send_fake_rst(stream, default_ttl, protect_path, flags, ip_id_mode)
    }

    #[allow(clippy::too_many_arguments)]
    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: DesyncFakeTcpOptions<'_>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()> {
        send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, ip_id_mode, wait)
    }
}

pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_fake_rst(stream, default_ttl, protect_path, to_runtime_flags(flags), ip_id_mode)
}

#[allow(clippy::too_many_arguments)]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: DesyncFakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    runtime_platform::raw_packet::send_fake_tcp(
        stream,
        original_prefix,
        fake_prefix,
        ttl,
        md5sig,
        default_ttl,
        to_runtime_fake_options(options),
        ip_id_mode,
        wait,
    )
}

fn to_runtime_fake_options<'a>(options: DesyncFakeTcpOptions<'a>) -> runtime_platform::raw_packet::FakeTcpOptions<'a> {
    runtime_platform::raw_packet::FakeTcpOptions {
        secondary_fake_prefix: options.secondary_fake_prefix,
        timestamp_delta_ticks: options.timestamp_delta_ticks,
        protect_path: options.protect_path,
        fake_flags: to_runtime_flags(options.fake_flags),
        orig_flags: to_runtime_flags(options.orig_flags),
        require_raw_path: options.require_raw_path,
        force_raw_original: options.force_raw_original,
        ipv4_identifications: options.ipv4_identifications,
    }
}
