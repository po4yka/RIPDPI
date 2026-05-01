use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;

use crate::runtime_policy::autolearn::normalize_learned_host;
use crate::runtime_policy::matching::group_matches;
use crate::runtime_policy::selection::host_preference::select_host_route;
use crate::runtime_policy::{ConnectionRoute, RuntimePolicy, TransportProtocol};

impl RuntimePolicy {
    pub fn select_initial(
        &mut self,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> Option<ConnectionRoute> {
        if let Some(normalized_host) =
            host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
        {
            if let Some(route) =
                select_host_route(self, config, &normalized_host, dest, payload, allow_unknown_payload, transport)
            {
                return Some(route);
            }
        } else if let Some(route) = self.lookup_and_prune(config, dest) {
            let group = config.groups.get(route.group_index)?;
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                return Some(route);
            }
        }

        let mut attempted_mask = 0u64;
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            if self.detect_for(config, idx) != 0 {
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                return Some(ConnectionRoute { group_index: idx, attempted_mask });
            }
            attempted_mask |= group.bit;
        }
        None
    }
}
