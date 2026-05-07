use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{TcpActivationState as DesyncTcpActivationState, TcpPlatformCapabilities};

use crate::platform as runtime_platform;

use super::{RuntimeTcpDesyncPlatform, TcpActivationProbe};

impl TcpPlatformCapabilities for RuntimeTcpDesyncPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        detect_default_ttl()
    }

    fn seqovl_supported(&self) -> bool {
        seqovl_supported()
    }

    fn supports_fake_retransmit(&self) -> bool {
        supports_fake_retransmit()
    }

    fn tcp_segment_hint(&self, stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
        tcp_segment_hint_result(stream)
    }

    fn tcp_activation_state(&self, stream: &TcpStream) -> io::Result<Option<DesyncTcpActivationState>> {
        tcp_activation_state_result(stream)
    }
}

pub fn detect_default_ttl() -> Option<u8> {
    runtime_platform::capability::detect_default_ttl().ok()
}

pub fn seqovl_supported() -> bool {
    runtime_platform::tcp::seqovl_supported()
}

pub fn supports_fake_retransmit() -> bool {
    runtime_platform::tcp::supports_fake_retransmit()
}

pub fn tcp_segment_hint_result(stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
    runtime_platform::tcp::tcp_segment_hint(stream)
}

pub fn tcp_activation_state_result(stream: &TcpStream) -> io::Result<Option<DesyncTcpActivationState>> {
    runtime_platform::tcp::tcp_activation_state(stream).map(|state| {
        state.map(|state| DesyncTcpActivationState {
            has_timestamp: state.has_timestamp,
            window_size: state.window_size,
            mss: state.mss,
        })
    })
}

pub fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    runtime_platform::tcp::tcp_segment_hint(stream).ok().flatten()
}

pub fn tcp_activation_state(stream: &TcpStream) -> Option<TcpActivationProbe> {
    runtime_platform::tcp::tcp_activation_state(stream).ok().flatten().map(|state| TcpActivationProbe {
        has_timestamp: state.has_timestamp,
        window_size: state.window_size,
        mss: state.mss,
    })
}
