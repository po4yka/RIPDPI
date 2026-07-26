use crate::dns_cache::DnsCache;

pub(in crate::io_loop) fn sync_direct_dns_mapping_generation(
    cache: Option<&mut DnsCache>,
    active_generation: &mut Option<u64>,
) {
    let current_generation = crate::tunnel_api::direct_dns_binding::current_direct_dns_generation();
    if *active_generation == current_generation {
        return;
    }
    if let Some(cache) = cache {
        cache.reset_unleased();
    }
    *active_generation = current_generation;
}
