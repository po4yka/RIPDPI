pub(crate) use super::*;
#[derive(Clone, Default)]
pub struct ShadowTlsRelayConfig {
    pub password: Option<String>,
    pub inner_profile_id: String,
    pub inner: Option<ResolvedShadowTlsInnerRelayConfig>,
}

impl_redacted_debug!(ShadowTlsRelayConfig { inner_profile_id, inner });
