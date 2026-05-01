use ripdpi_tunnel_config::TunnelConfig;

use crate::config::payload::TunnelConfigPayload;

pub(crate) fn tunnel_config_from_payload(payload: &TunnelConfigPayload) -> TunnelConfig {
    TunnelConfig {
        name: payload.tunnel_name.clone(),
        mtu: payload.tunnel_mtu,
        multi_queue: payload.multi_queue,
        ipv4: payload.tunnel_ipv4.clone(),
        ipv6: payload.tunnel_ipv6.clone(),
        post_up_script: None,
        pre_down_script: None,
    }
}
