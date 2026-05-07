use ripdpi_proxy_runtime_adapter::model::proxy_config::morph_policy;
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyMorphPolicy;

use crate::runtime::state::RuntimeState;

pub(super) fn current_morph_policy(state: &RuntimeState) -> Option<&ProxyMorphPolicy> {
    morph_policy(state.runtime_context.as_ref())
}
