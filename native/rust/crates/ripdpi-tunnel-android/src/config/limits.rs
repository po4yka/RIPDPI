use crate::config::payload::TunnelConfigPayload;

mod misc;

pub(crate) use misc::misc_config_from_payload;

pub(crate) fn validate_limits(payload: &TunnelConfigPayload) -> Result<(), String> {
    if payload.tunnel_mtu < 68 || payload.tunnel_mtu > 65535 {
        return Err(format!("tunnelMtu must be between 68 and 65535, got {}", payload.tunnel_mtu));
    }
    if payload.task_stack_size < 8_192 || payload.task_stack_size > 16_777_216 {
        return Err(format!("taskStackSize must be between 8192 and 16777216, got {}", payload.task_stack_size));
    }
    if let Some(timeout) = payload.connect_timeout_ms
        && (timeout == 0 || timeout > 300_000)
    {
        return Err(format!("connectTimeoutMs must be between 1 and 300000, got {timeout}"));
    }
    if let Some(timeout) = payload.tcp_read_write_timeout_ms
        && (timeout == 0 || timeout > 300_000)
    {
        return Err(format!("tcpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
    }
    if let Some(timeout) = payload.udp_read_write_timeout_ms
        && (timeout == 0 || timeout > 300_000)
    {
        return Err(format!("udpReadWriteTimeoutMs must be between 1 and 300000, got {timeout}"));
    }
    if let Some(limit) = payload.limit_nofile
        && !(64..=1_048_576).contains(&limit)
    {
        return Err(format!("limitNofile must be between 64 and 1048576, got {limit}"));
    }
    if let Some(max) = payload.max_session_count
        && (max == 0 || max > 100_000)
    {
        return Err(format!("maxSessionCount must be between 1 and 100000, got {max}"));
    }
    if let Some(size) = payload.tcp_buffer_size
        && (size == 0 || size > 16_777_216)
    {
        return Err(format!("tcpBufferSize must be between 1 and 16777216, got {size}"));
    }
    if let Some(size) = payload.udp_recv_buffer_size
        && (size == 0 || size > 16_777_216)
    {
        return Err(format!("udpRecvBufferSize must be between 1 and 16777216, got {size}"));
    }
    Ok(())
}
