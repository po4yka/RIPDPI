#[derive(Debug, Clone, Default)]
pub struct ShadowTlsRelayConfig {
    pub password: Option<String>,
    pub inner_profile_id: String,
    pub inner: Option<ResolvedShadowTlsInnerRelayConfig>,
}
