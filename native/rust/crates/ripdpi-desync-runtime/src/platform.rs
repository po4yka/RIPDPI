mod dispatch_wrappers;
mod registry;
#[cfg(test)]
mod test_support;
mod r#trait;
#[cfg(test)]
mod trait_split_parity;
mod types;

pub use dispatch_wrappers::{
    detect_default_ttl, send_fake_rst, send_fake_tcp, send_flagged_tcp_payload, send_ip_fragmented_tcp,
    send_multi_disorder_tcp, send_ordered_tcp_segments, send_seqovl_tcp, seqovl_supported, set_tcp_md5sig,
    set_tcp_window_clamp, supports_fake_retransmit, tcp_activation_state, tcp_segment_hint, wait_tcp_stage,
};
pub use r#trait::{
    TcpDesyncPlatform, TcpFakeSender, TcpFragmentSender, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions,
};
pub use registry::with_tcp_desync_platform;
pub use types::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait,
};
