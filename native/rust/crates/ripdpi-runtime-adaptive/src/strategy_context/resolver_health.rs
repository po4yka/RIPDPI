use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_strategy::strategy_evolver::ResolverHealthClass;

pub(crate) fn resolver_health_context(runtime_context: Option<&ProxyRuntimeContext>) -> ResolverHealthClass {
    match runtime_context.and_then(|context| context.encrypted_dns.as_ref()) {
        Some(_) => ResolverHealthClass::Healthy,
        None => ResolverHealthClass::Unknown,
    }
}
