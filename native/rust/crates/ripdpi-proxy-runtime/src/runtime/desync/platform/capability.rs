use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::TcpActivationState as DesyncTcpActivationState;

use ripdpi_runtime_platform as runtime_platform;

pub(crate) fn detect_default_ttl() -> Option<u8> {
    runtime_platform::detect_default_ttl().ok()
}

pub(crate) fn seqovl_supported() -> bool {
    runtime_platform::seqovl_supported()
}

pub(crate) fn supports_fake_retransmit() -> bool {
    runtime_platform::supports_fake_retransmit()
}

pub(crate) fn tcp_segment_hint_result(stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
    runtime_platform::tcp_segment_hint(stream)
}

pub(crate) fn tcp_activation_state_result(stream: &TcpStream) -> io::Result<Option<DesyncTcpActivationState>> {
    runtime_platform::tcp_activation_state(stream).map(|state| {
        state.map(|state| DesyncTcpActivationState {
            has_timestamp: state.has_timestamp,
            window_size: state.window_size,
            mss: state.mss,
        })
    })
}

pub(crate) fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    runtime_platform::tcp_segment_hint(stream).ok().flatten()
}

pub(crate) fn tcp_activation_state(stream: &TcpStream) -> Option<runtime_platform::TcpActivationState> {
    runtime_platform::tcp_activation_state(stream).ok().flatten()
}
