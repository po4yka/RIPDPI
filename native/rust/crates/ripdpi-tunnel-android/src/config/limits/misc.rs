use ripdpi_tunnel_config::MiscConfig;

use crate::config::payload::TunnelConfigPayload;

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
    misc.lua_script_base_dir = payload.lua_script_base_dir.clone().filter(|value| !value.trim().is_empty());
    misc
}

#[cfg(test)]
mod tests {
    use super::misc_config_from_payload;
    use crate::config::payload::sample_payload;

    #[test]
    fn threads_absolute_lua_script_base_dir_from_payload() {
        let mut payload = sample_payload();
        payload.lua_script_base_dir = Some("/data/user/0/com.poyka.ripdpi/files/lua".to_string());

        let misc = misc_config_from_payload(&payload);

        assert_eq!(misc.lua_script_base_dir.as_deref(), Some("/data/user/0/com.poyka.ripdpi/files/lua"));
    }

    #[test]
    fn blank_lua_script_base_dir_is_dropped() {
        let mut payload = sample_payload();
        payload.lua_script_base_dir = Some("   ".to_string());

        let misc = misc_config_from_payload(&payload);

        assert_eq!(misc.lua_script_base_dir, None);
    }
}
