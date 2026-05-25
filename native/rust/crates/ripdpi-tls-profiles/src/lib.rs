use thiserror::Error;

mod apply;
mod builder;
mod chrome;
mod ech;
mod edge;
mod firefox;
mod invariants;
mod profile;
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
    configure_boring_ech, require_ech_backend_support, EchConfigError, EchOutboundError, OutboundEchBackend,
    OutboundEchConfig,
};
pub use profile::{
    profile_catalog, profile_metadata, ProfileCatalog, ProfileConfig, ProfileInvariantStatus, ProfileMetadata,
    ProfileParityTargets, ProfileTemplateMetadata, AVAILABLE_PROFILES,
};
pub use record_choreography::{
    apply_record_choreography, plan_first_flight, planned_record_payload_boundaries, planned_record_payload_lengths,
    selected_record_choreography, RecordChoreography, TlsTemplateFirstFlightPlan,
};
pub use rotation::{select_profile_for_connection, select_rotated_profile, select_rotated_profile_with_set};

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
