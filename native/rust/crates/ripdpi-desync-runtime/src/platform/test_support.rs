use std::io;
use std::io::Write;
use std::net::TcpStream;
#[cfg(any(target_os = "android", target_os = "linux"))]
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

use super::r#trait::{TcpFakeSender, TcpFragmentSender, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions};
use super::types::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait,
};

pub(super) struct TestTcpDesyncPlatform;

impl TcpPlatformCapabilities for TestTcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        Some(64)
    }

    fn seqovl_supported(&self) -> bool {
        false
    }

    fn supports_fake_retransmit(&self) -> bool {
        cfg!(any(target_os = "linux", target_os = "android"))
    }

    fn tcp_segment_hint(&self, _stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
        Ok(None)
    }

    fn tcp_activation_state(&self, _stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
        Ok(None)
    }
}

impl TcpSocketOptions for TestTcpDesyncPlatform {
    fn set_tcp_md5sig(&self, _stream: &TcpStream, _key_len: u16) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

    fn set_tcp_window_clamp(&self, _stream: &TcpStream, _size: u32) -> io::Result<()> {
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            let value = _size as libc::c_int;
            let rc = unsafe {
                libc::setsockopt(
                    _stream.as_raw_fd(),
                    libc::IPPROTO_TCP,
                    libc::TCP_WINDOW_CLAMP,
                    (&value as *const libc::c_int).cast(),
                    std::mem::size_of_val(&value) as libc::socklen_t,
                )
            };
            if rc == -1 { Err(io::Error::last_os_error()) } else { Ok(()) }
        }

        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

    fn wait_tcp_stage(&self, _stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

impl TcpFakeSender for TestTcpDesyncPlatform {
    fn send_fake_rst(
        &self,
        _stream: &TcpStream,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        _fake_prefix: &[u8],
        _ttl: u8,
        _md5sig: bool,
        _default_ttl: u8,
        options: FakeTcpOptions<'_>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
        if !options.fake_flags.is_empty() || !options.orig_flags.is_empty() {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "flagged tcp payload unsupported"));
        }
        let mut stream = stream;
        stream.write_all(original_prefix)
    }
}

impl TcpPayloadSender for TestTcpDesyncPlatform {
    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[OrderedTcpSegment<'_>],
        _original_payload_len: usize,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _timestamp_delta_ticks: Option<i32>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
        let mut stream = stream;
        for segment in segments.iter().filter(|segment| !segment.use_fake_timestamp) {
            stream.write_all(segment.payload)?;
        }
        Ok(())
    }

    fn send_flagged_tcp_payload(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "flagged tcp payload unsupported"))
    }

    fn send_seqovl_tcp(
        &self,
        _stream: &TcpStream,
        _real_chunk: &[u8],
        _fake_prefix: &[u8],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "packet-owned TCP desync requires TCP_INFO support"))
    }
}

impl TcpFragmentSender for TestTcpDesyncPlatform {
    fn send_ip_fragmented_tcp(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _split_offset: usize,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _disorder: bool,
        _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "packet-owned TCP desync requires TCP_INFO support"))
    }

    fn send_multi_disorder_tcp(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _segments: &[TcpPayloadSegment],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _inter_segment_delay_ms: u32,
        _md5sig: bool,
        _original_flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "packet-owned TCP desync requires TCP_INFO support"))
    }
}
