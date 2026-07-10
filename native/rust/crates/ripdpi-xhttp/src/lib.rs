#![forbid(unsafe_code)]

use sha2::{Digest, Sha256};

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
    XhttpSocketProtector, XhttpTlsConfig, XmuxConfig,
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

pub fn catalog_validated_tls_profile(profile: &str, authority: &str) -> bool {
    let resolved = if ripdpi_tls_profiles::is_rotating_profile(profile) {
        ripdpi_tls_profiles::resolve_connection_profile(profile, authority)
    } else if ripdpi_tls_profiles::AVAILABLE_PROFILES.contains(&profile) {
        profile
    } else {
        return false;
    };
    ripdpi_tls_profiles::profile_invariants_pass(resolved)
}

pub fn reality_target_digest(target: &str) -> String {
    Sha256::digest(target.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests;
