#![forbid(unsafe_code)]

mod fake_profiles;
mod http;
mod quic;
mod tls;
pub(crate) mod tls_nom;
mod types;
mod util;

pub use fake_profiles::{
    http_fake_profile_bytes, tls_fake_profile_bytes, udp_fake_profile_bytes, HttpFakeProfile, TlsFakeProfile,
    UdpFakeProfile,
};
pub use http::{http_marker_info, is_http, is_http_redirect, mod_http_like_c, parse_http, second_level_domain_span};
pub use quic::{
    build_quic_initial_from_tls, build_realistic_quic_initial, default_fake_quic_compat, is_quic_initial,
    parse_quic_initial, tamper_quic_initial_split_sni, tamper_quic_version,
};
pub use tls::{
    change_tls_sni_seeded_like_c, duplicate_tls_session_id_like_c, is_tls_client_hello, is_tls_server_hello,
    padencap_tls_like_c, parse_tls, part_tls_like_c, randomize_tls_seeded_like_c, randomize_tls_sni_seeded_like_c,
    tls_marker_info, tls_session_id_mismatch, tune_tls_padding_size_like_c,
};
pub use types::{
    HttpHost, HttpMarkerInfo, OracleRng, PacketMutation, QuicInitialInfo, TlsMarkerInfo, DEFAULT_FAKE_HTTP,
    DEFAULT_FAKE_QUIC_COMPAT_LEN, DEFAULT_FAKE_TLS, DEFAULT_FAKE_UDP, IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP,
    MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL, QUIC_V1_VERSION, QUIC_V2_VERSION,
};
