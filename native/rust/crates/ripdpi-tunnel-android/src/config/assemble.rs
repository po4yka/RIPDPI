use ripdpi_tunnel_config::Config;

use super::payload::TunnelConfigPayload;
use super::{dns, limits, socks, split_dns, tunnel, validation};

pub(crate) fn config_from_payload(payload: TunnelConfigPayload) -> Result<Config, String> {
    validation::validate_payload(&payload)?;
    let mapdns = dns::mapdns_config_from_payload(&payload);
    let split_dns_policy = split_dns::split_dns_config_from_payload(&payload)?;
    split_dns::validate_runtime_binding(split_dns_policy.as_ref(), mapdns.as_ref())?;
    let config = Config {
        tunnel: tunnel::tunnel_config_from_payload(&payload),
        socks5: socks::socks5_config_from_payload(&payload),
        mapdns,
        split_dns_policy,
        misc: limits::misc_config_from_payload(&payload),
    };
    config.validate().map_err(|err| err.to_string())?;
    Ok(config)
}
