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
    .map_err(|error| map_fake_tcp_progress(error, original_prefix.len()))
}

fn map_fake_tcp_progress(error: io::Error, payload_len: usize) -> io::Error {
    let Some(progress) =
        error.get_ref().and_then(|source| source.downcast_ref::<runtime_platform::raw_packet::TcpSendProgressError>())
    else {
        return error;
    };
    if progress.bytes_committed > payload_len {
        return io::Error::new(io::ErrorKind::InvalidData, "fake TCP progress exceeds its original payload");
    }
    let source = progress.source.raw_os_error().map_or_else(
        || io::Error::new(progress.source.kind(), progress.source.to_string()),
        io::Error::from_raw_os_error,
    );
    io::Error::new(
        error.kind(),
        ripdpi_desync_runtime::WriteProgressError { written: progress.bytes_committed, source },
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fake_tcp_progress_reaches_the_runtime_error_contract() {
        let error = io::Error::new(
            io::ErrorKind::BrokenPipe,
            runtime_platform::raw_packet::TcpSendProgressError {
                bytes_committed: 3,
                source: io::Error::from_raw_os_error(libc::EPIPE),
            },
        );
        let mapped = map_fake_tcp_progress(error, 5);
        let progress = mapped
            .get_ref()
            .and_then(|source| source.downcast_ref::<ripdpi_desync_runtime::WriteProgressError>())
            .expect("runtime progress");
        assert_eq!(progress.written, 3);
        assert_eq!(progress.source.raw_os_error(), Some(libc::EPIPE));
        assert_eq!(mapped.raw_os_error(), None);
    }

    #[test]
    fn fake_tcp_progress_cannot_exceed_the_sent_payload() {
        let error = io::Error::other(runtime_platform::raw_packet::TcpSendProgressError {
            bytes_committed: 6,
            source: io::Error::other("failed send"),
        });
        assert_eq!(map_fake_tcp_progress(error, 5).kind(), io::ErrorKind::InvalidData);
    }
}
