#![forbid(unsafe_code)]

use thiserror::Error;

mod apply;
mod builder;
mod chrome;
pub mod ech;
mod edge;
mod firefox;
mod invariants;
mod pq_kem;
mod profile;
mod reality_ech;
mod record_choreography;
mod rotation;
mod safari;
mod trust;

#[cfg(test)]
mod packet_parity_tests;
#[cfg(test)]
mod tests;

pub use builder::{build_connector, configure_builder};
pub use ech::{
    EchConfigError, EchFacadeError, EchLookupOutcome, EchLookupRequest, EchLookupTransport, EchOutboundError,
    EchPolicy, EchPublicNameVerifier, EchRejectedHandshake, EchRetryState, EchSetup, OutboundEchBackend,
    OutboundEchConfig, OutboundEchResolver, configure_boring_ech, configure_ech, configure_rustls_ech,
    extract_boring_ech_rejection, prepare_ech_retry, require_ech_backend_support, resolve_outbound_ech,
};
pub use pq_kem::{
    SECP256R1_GROUP_ID, X25519_GROUP_ID, X25519MLKEM768_GROUP_ID, apply_kem_groups, normalize_kem_groups,
    note_pq_kem_negotiation, pq_kem_negotiated_count,
};
pub use profile::{
    AVAILABLE_PROFILES, ProfileCatalog, ProfileConfig, ProfileInvariantStatus, ProfileMetadata, ProfileParityTargets,
    ProfileTemplateMetadata, profile_catalog, profile_metadata,
};
pub use reality_ech::{CoverEchEvidence, RealityEchParity, reality_ech_parity};
pub use record_choreography::{
    RecordChoreography, TlsTemplateFirstFlightPlan, apply_record_choreography, plan_first_flight,
    planned_record_payload_boundaries, planned_record_payload_lengths, selected_record_choreography,
};
pub use rotation::{
    ROTATING_PROFILE_MARKER, RotatingProfileSelector, fingerprint_rotation_count, is_rotating_profile,
    resolve_connection_profile, select_profile_for_connection, select_rotated_profile, select_rotated_profile_with_set,
};

#[derive(Debug, Error)]
pub enum Error {
    #[error("BoringSSL error: {0}")]
    Ssl(#[from] boring::error::ErrorStack),
    #[error("TLS profile invariant failed for {profile}: {reason}")]
    Invariant { profile: &'static str, reason: &'static str },
}

pub fn profile_catalog_version() -> &'static str {
    profile::profile_catalog().version
}

pub fn selected_profile_metadata(profile: &str) -> ProfileMetadata {
    profile::profile_metadata(profile)
}

pub fn selected_profile_config(profile: &str) -> &'static ProfileConfig {
    profile::lookup_profile(profile)
}

/// Invariant gate over a profile input name. Accepts every published
/// [`AVAILABLE_PROFILES`] entry plus the documented `"native_default"` alias.
/// Unknown names fail even though connection-time lookup falls back to
/// `chrome_stable`, so configuration typos stay visible at validation time.
pub fn profile_invariants_pass(profile: &str) -> bool {
    profile::is_known_profile_input(profile)
        && invariants::validate_profile_config(profile::lookup_profile(profile)).is_ok()
}
