use ripdpi_tunnel_config::MiscConfig;

use crate::config::payload::TunnelConfigPayload;

pub(crate) fn validate_limits(payload: &TunnelConfigPayload) -> Result<(), String> {
    if payload.tunnel_mtu < 68 || payload.tunnel_mtu > 65535 {
        return Err(format!("tunnelMtu must be between 68 and 65535, got {}", payload.tunnel_mtu));
    }
    if payload.task_stack_size < 8_192 || payload.task_stack_size > 16_777_216 {
        return Err(format!("taskStackSize must be between 8192 and 16777216, got {}", payload.task_stack_size));
    }
    if let Some(timeout) = payload.connect_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("connectTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(timeout) = payload.tcp_read_write_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("tcpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(timeout) = payload.udp_read_write_timeout_ms {
        if timeout == 0 || timeout > 300_000 {
            return Err(format!("udpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
        }
    }
    if let Some(limit) = payload.limit_nofile {
        if !(64..=1_048_576).contains(&limit) {
            return Err(format!("limitNofile must be between 64 and 1048576, got {limit}"));
        }
    }
    if let Some(max) = payload.max_session_count {
        if max == 0 || max > 100_000 {
            return Err(format!("maxSessionCount must be between 1 and 100000, got {max}"));
        }
    }
    if let Some(size) = payload.tcp_buffer_size {
        if size == 0 || size > 16_777_216 {
            return Err(format!("tcpBufferSize must be between 1 and 16777216, got {size}"));
        }
    }
    if let Some(size) = payload.udp_recv_buffer_size {
        if size == 0 || size > 16_777_216 {
            return Err(format!("udpRecvBufferSize must be between 1 and 16777216, got {size}"));
        }
    }
    Ok(())
}

pub(crate) fn misc_config_from_payload(payload: &TunnelConfigPayload) -> MiscConfig {
    let mut misc = MiscConfig {
        task_stack_size: payload.task_stack_size,
        log_level: payload.log_level.clone(),
        ..MiscConfig::default()
    };
    if let Some(value) = payload.tcp_buffer_size {
        misc.tcp_buffer_size = value;
    }
    if let Some(value) = payload.udp_recv_buffer_size {
        misc.udp_recv_buffer_size = value;
    }
    if let Some(value) = payload.udp_copy_buffer_nums {
        misc.udp_copy_buffer_nums = value;
    }
    if let Some(value) = payload.max_session_count {
        misc.max_session_count = value;
    }
    if let Some(value) = payload.connect_timeout_ms {
        misc.connect_timeout = value;
    }
    if let Some(value) = payload.tcp_read_write_timeout_ms {
        misc.tcp_read_write_timeout = value;
    }
    if let Some(value) = payload.udp_read_write_timeout_ms {
        misc.udp_read_write_timeout = value;
    }
    if let Some(value) = payload.limit_nofile {
        misc.limit_nofile = value;
    }
    if let Some(value) = payload.filter_injected_resets {
        misc.filter_injected_resets = value;
    }
    misc.strategy_chain_yaml = payload.strategy_chain_yaml.clone().filter(|value| !value.trim().is_empty());
    misc.protect_path = payload.protect_path.clone().filter(|value| !value.trim().is_empty());
    misc.root_helper_socket_path = payload.root_helper_socket_path.clone().filter(|value| !value.trim().is_empty());
    misc
}
