mod defaults;
mod dns;
mod limits;
mod log_context;
mod payload;
mod socks;
mod split_dns;
#[cfg(test)]
mod tests;
mod tunnel;
mod validation;

use ripdpi_tunnel_config::Config;

pub(crate) use dns::mapdns_resolver_protocol;
pub(crate) use log_context::{TunnelLogContext, sanitize_log_context};
#[cfg(test)]
pub(crate) use payload::sample_payload;
pub(crate) use payload::{TunnelConfigPayload, parse_tunnel_config_json};

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
