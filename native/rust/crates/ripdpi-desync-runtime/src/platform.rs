use std::cell::RefCell;
use std::io;
#[cfg(test)]
use std::io::Write;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

pub type TcpStageWait = (bool, Duration);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpFlagOverrides {
    pub set: u16,
    pub unset: u16,
}

impl TcpFlagOverrides {
    pub const fn is_empty(self) -> bool {
        self.set == 0 && self.unset == 0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OrderedTcpSegment<'a> {
    pub payload: &'a [u8],
    pub ttl: u8,
    pub flags: TcpFlagOverrides,
    pub sequence_offset: usize,
    pub use_fake_timestamp: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FakeTcpOptions<'a> {
    pub secondary_fake_prefix: Option<&'a [u8]>,
    pub timestamp_delta_ticks: Option<i32>,
    pub protect_path: Option<&'a str>,
    pub fake_flags: TcpFlagOverrides,
    pub orig_flags: TcpFlagOverrides,
    pub require_raw_path: bool,
    pub force_raw_original: bool,
    pub ipv4_identifications: Vec<u16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpPayloadSegment {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpActivationState {
    pub has_timestamp: Option<bool>,
    pub window_size: Option<i64>,
    pub mss: Option<i64>,
}

#[allow(clippy::too_many_arguments)]
pub trait TcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8>;
    fn seqovl_supported(&self) -> bool;
    fn supports_fake_retransmit(&self) -> bool;
    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>>;
    fn tcp_activation_state(&self, stream: &TcpStream) -> io::Result<Option<TcpActivationState>>;
    fn set_tcp_md5sig(&self, stream: &TcpStream, key_len: u16) -> io::Result<()>;
    fn set_tcp_window_clamp(&self, stream: &TcpStream, size: u32) -> io::Result<()>;
    fn wait_tcp_stage(&self, stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()>;
    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        default_ttl: u8,
        protect_path: Option<&str>,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: FakeTcpOptions<'_>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()>;
    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[OrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
        wait: TcpStageWait,
    ) -> io::Result<()>;
    fn send_flagged_tcp_payload(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_seqovl_tcp(
        &self,
        stream: &TcpStream,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        protect_path: Option<&str>,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        segments: &[TcpPayloadSegment],
        default_ttl: u8,
        protect_path: Option<&str>,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        original_flags: TcpFlagOverrides,
        ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()>;
}

thread_local! {
    static CURRENT_PLATFORM: RefCell<Option<*const dyn TcpDesyncPlatform>> = const { RefCell::new(None) };
}

pub fn with_tcp_desync_platform<P, R>(platform: &P, f: impl FnOnce() -> R) -> R
where
    P: TcpDesyncPlatform + 'static,
{
    CURRENT_PLATFORM.with(|slot| {
        let platform = platform as &dyn TcpDesyncPlatform;
        let previous = slot.replace(Some(platform as *const dyn TcpDesyncPlatform));
        struct Restore<'a>(&'a RefCell<Option<*const dyn TcpDesyncPlatform>>, Option<*const dyn TcpDesyncPlatform>);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                self.0.replace(self.1);
            }
        }
        let _restore = Restore(slot, previous);
        f()
    })
}

fn with_current<R>(f: impl FnOnce(&dyn TcpDesyncPlatform) -> R) -> R {
    CURRENT_PLATFORM.with(|slot| {
        if let Some(pointer) = *slot.borrow() {
            // SAFETY: `with_tcp_desync_platform` installs a pointer that is valid
            // for the duration of the synchronous execution closure.
            let platform = unsafe { &*pointer };
            return f(platform);
        }
        #[cfg(test)]
        {
            f(&TestTcpDesyncPlatform)
        }
        #[cfg(not(test))]
        {
            panic!("tcp desync platform not installed");
        }
    })
}

#[cfg(test)]
struct TestTcpDesyncPlatform;

#[cfg(test)]
impl TcpDesyncPlatform for TestTcpDesyncPlatform {
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

    fn set_tcp_md5sig(&self, _stream: &TcpStream, _key_len: u16) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

    fn set_tcp_window_clamp(&self, _stream: &TcpStream, _size: u32) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

    fn wait_tcp_stage(&self, _stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }

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

pub fn detect_default_ttl() -> Option<u8> {
    with_current(|platform| platform.detect_default_ttl())
}

pub fn seqovl_supported() -> bool {
    with_current(|platform| platform.seqovl_supported())
}

pub fn supports_fake_retransmit() -> bool {
    with_current(|platform| platform.supports_fake_retransmit())
}

pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    with_current(|platform| platform.tcp_segment_hint(stream))
}

pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    with_current(|platform| platform.tcp_activation_state(stream))
}

pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    with_current(|platform| platform.set_tcp_md5sig(stream, key_len))
}

pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    with_current(|platform| platform.set_tcp_window_clamp(stream, size))
}

pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    with_current(|platform| platform.wait_tcp_stage(stream, wait_send, await_interval))
}

pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| platform.send_fake_rst(stream, default_ttl, protect_path, flags, ip_id_mode))
}

#[allow(clippy::too_many_arguments)]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            options,
            ip_id_mode,
            wait,
        )
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_ordered_tcp_segments(
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
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_seqovl_tcp(stream, real_chunk, fake_prefix, default_ttl, protect_path, md5sig, flags, ip_id_mode)
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_ip_fragmented_tcp(
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
    })
}

#[allow(clippy::too_many_arguments)]
pub fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    original_flags: TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    with_current(|platform| {
        platform.send_multi_disorder_tcp(
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
    })
}
