pub(crate) fn default_tunnel_name() -> String {
    "tun0".to_string()
}

pub(crate) const fn default_tunnel_mtu() -> u32 {
    1500
}

pub(crate) const fn default_multi_queue() -> bool {
    false
}

pub(crate) fn default_socks5_address() -> String {
    "127.0.0.1".to_string()
}

pub(crate) fn default_socks5_udp() -> Option<String> {
    Some("udp".to_string())
}

pub(crate) const fn default_task_stack_size() -> u32 {
    81_920
}

pub(crate) fn default_log_level() -> String {
    "warn".to_string()
}
