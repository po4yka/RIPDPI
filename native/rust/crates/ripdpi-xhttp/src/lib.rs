#![forbid(unsafe_code)]

mod client;
mod config;
mod connect;
mod finalmask;
mod grpc;
mod h2_body;
mod pool;
mod relay;

pub use client::{XhttpClient, connect_reality, connect_tls};
pub use config::{
    AsyncIo, ConfigError, FinalmaskConfig, ProtocolModeParseError, XhttpProtocolMode, XhttpRealityConfig,
    XhttpTlsConfig, XmuxConfig,
};
#[doc(hidden)]
pub use finalmask::{__fuzz_decode_finalmask_payload, __fuzz_parse_finalmask_spec};
pub use grpc::{
    GrpcFramingError, GrpcTransport, GrpcTransportConfig, GrpcWireHalves, HunkDecoder, encode_hunk,
    encode_hunk_to_bytes, tun_path,
};
pub use relay::XhttpStream;

pub fn tls_profile_catalog_version() -> &'static str {
    ripdpi_tls_profiles::profile_catalog_version()
}

#[cfg(test)]
mod tests;
