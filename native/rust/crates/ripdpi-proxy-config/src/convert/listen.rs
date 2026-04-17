use std::net::IpAddr;
use std::str::FromStr;

use ripdpi_config::RuntimeConfig;

use crate::types::{ProxyConfigError, ProxyUiListenConfig};

pub(crate) fn apply_listen_section(
    config: &mut RuntimeConfig,
    listen: &ProxyUiListenConfig,
) -> Result<(), ProxyConfigError> {
    let listen_ip =
        IpAddr::from_str(&listen.ip).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy IP".to_string()))?;
    config.network.listen.listen_ip = listen_ip;
    config.network.listen.listen_port =
        u16::try_from(listen.port).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()))?;
    config.network.listen.auth_token = listen.auth_token.clone().filter(|token| !token.is_empty());

    if config.network.listen.listen_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()));
    }
    if listen.max_connections <= 0 || listen.max_connections > 4096 {
        return Err(ProxyConfigError::InvalidConfig("maxConnections must be in 1..=4096".to_string()));
    }

    config.network.max_open = listen.max_connections;
    config.network.buffer_size = usize::try_from(listen.buffer_size)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid bufferSize".to_string()))?;
    if config.network.buffer_size == 0 || config.network.buffer_size > 1_048_576 {
        return Err(ProxyConfigError::InvalidConfig("bufferSize must be in 1..=1048576".to_string()));
    }

    config.network.tfo = listen.tcp_fast_open;
    if listen.custom_ttl {
        let ttl = u8::try_from(listen.default_ttl)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid defaultTtl".to_string()))?;
        if ttl == 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "defaultTtl must be positive when customTtl is enabled".to_string(),
            ));
        }
        config.network.default_ttl = ttl;
        config.network.custom_ttl = true;
    }

    Ok(())
}
