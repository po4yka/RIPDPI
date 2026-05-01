use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct MiscConfig {
    #[serde(default = "default_task_stack_size")]
    pub task_stack_size: u32,
    #[serde(default = "default_tcp_buffer_size")]
    pub tcp_buffer_size: u32,
    #[serde(default = "default_udp_recv_buffer_size")]
    pub udp_recv_buffer_size: u32,
    #[serde(default = "default_udp_copy_buffer_nums")]
    pub udp_copy_buffer_nums: u32,
    #[serde(default)]
    pub max_session_count: u32,
    #[serde(default = "default_connect_timeout")]
    pub connect_timeout: u32,
    #[serde(default = "default_tcp_rw_timeout")]
    pub tcp_read_write_timeout: u32,
    #[serde(default = "default_udp_rw_timeout")]
    pub udp_read_write_timeout: u32,
    pub log_file: Option<String>,
    #[serde(default = "default_log_level")]
    pub log_level: String,
    pub pid_file: Option<String>,
    #[serde(default = "default_limit_nofile")]
    pub limit_nofile: u32,
    #[serde(default)]
    pub filter_injected_resets: bool,
}

impl Default for MiscConfig {
    fn default() -> Self {
        Self {
            task_stack_size: default_task_stack_size(),
            tcp_buffer_size: default_tcp_buffer_size(),
            udp_recv_buffer_size: default_udp_recv_buffer_size(),
            udp_copy_buffer_nums: default_udp_copy_buffer_nums(),
            max_session_count: 0,
            connect_timeout: default_connect_timeout(),
            tcp_read_write_timeout: default_tcp_rw_timeout(),
            udp_read_write_timeout: default_udp_rw_timeout(),
            log_file: None,
            log_level: default_log_level(),
            pid_file: None,
            limit_nofile: default_limit_nofile(),
            filter_injected_resets: false,
        }
    }
}

fn default_task_stack_size() -> u32 {
    86016
}

fn default_tcp_buffer_size() -> u32 {
    65536
}

fn default_udp_recv_buffer_size() -> u32 {
    524288
}

fn default_udp_copy_buffer_nums() -> u32 {
    10
}

fn default_connect_timeout() -> u32 {
    10000
}

fn default_tcp_rw_timeout() -> u32 {
    300000
}

fn default_udp_rw_timeout() -> u32 {
    60000
}

fn default_limit_nofile() -> u32 {
    65535
}

fn default_log_level() -> String {
    "warn".to_string()
}
