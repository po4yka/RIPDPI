mod fallback;
mod invariant;
mod transparent_tls;

use std::borrow::Cow;

use ripdpi_config::DesyncGroup;
use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;

use self::transparent_tls::transparent_tls_family_strategy;
#[cfg(any(not(test), feature = "loom"))]
use fallback::apply_tcp_capability_fallback;
#[cfg(all(test, not(feature = "loom")))]
pub(crate) use fallback::apply_tcp_capability_fallback;
#[cfg(all(test, not(feature = "loom")))]
pub(crate) use invariant::validate_transparent_tls_family;
#[cfg(any(not(test), feature = "loom"))]
use transparent_tls::apply_transparent_tls_family;
#[cfg(all(test, not(feature = "loom")))]
pub(crate) use transparent_tls::{
    apply_transparent_tls_family, transparent_tls_variant_with_seed, TransparentTlsFamilyError,
    TWO_PHASE_FIRST_WRITE_MAX, TWO_PHASE_FIRST_WRITE_MIN, TWO_PHASE_GAP_MS_MAX, TWO_PHASE_GAP_MS_MIN,
};

pub fn apply_tcp_capability_policy<'a>(
    group: &'a DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
    payload: &[u8],
    progress: OutboundProgress,
) -> (Cow<'a, DesyncGroup>, Option<&'static str>) {
    if !group.actions.tcp_chain.is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some() {
        return (apply_tcp_capability_fallback(group, capability), None);
    }

    if let Some(strategy_family) =
        capability.and_then(|value| transparent_tls_family_strategy(value, payload, progress))
    {
        match apply_transparent_tls_family(group, strategy_family, payload) {
            Ok(adjusted) => return (Cow::Owned(adjusted), Some(strategy_family)),
            Err(error) => {
                tracing::warn!(strategy_family, ?error, "skipping transparent tls family due to invalid plan");
            }
        }
    }

    (apply_tcp_capability_fallback(group, capability), None)
}
