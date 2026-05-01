mod defaults;
mod dns;
mod limits;
mod log_context;
mod payload;
mod socks;
#[cfg(test)]
mod tests;
mod tunnel;
mod validation;

use ripdpi_tunnel_config::Config;

pub(crate) use dns::mapdns_resolver_protocol;
pub(crate) use log_context::{sanitize_log_context, TunnelLogContext};
#[cfg(test)]
pub(crate) use payload::sample_payload;
pub(crate) use payload::{parse_tunnel_config_json, TunnelConfigPayload};

pub(crate) fn config_from_payload(payload: TunnelConfigPayload) -> Result<Config, String> {
    validation::validate_payload(&payload)?;
    Ok(Config {
        tunnel: tunnel::tunnel_config_from_payload(&payload),
        socks5: socks::socks5_config_from_payload(&payload),
        mapdns: dns::mapdns_config_from_payload(&payload),
        misc: limits::misc_config_from_payload(&payload),
    })
}
