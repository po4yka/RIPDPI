use ripdpi_proxy_config::ProxyMorphPolicy;

use crate::runtime::state::RuntimeState;

pub(super) fn current_morph_policy(state: &RuntimeState) -> Option<&ProxyMorphPolicy> {
    state.runtime_context.as_ref()?.morph_policy.as_ref()
}
