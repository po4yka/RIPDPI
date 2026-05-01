use std::time::Duration;

#[derive(Debug, Clone, Copy)]
pub struct RelayPoolConfig {
    pub max_active_leases: usize,
    pub idle_timeout: Duration,
}

impl Default for RelayPoolConfig {
    fn default() -> Self {
        Self { max_active_leases: 64, idle_timeout: Duration::from_secs(30) }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct RelayPoolHealth {
    pub idle_streams: usize,
    pub busy_streams: usize,
    pub evictions: u64,
    pub idle_timeout: Duration,
    pub backpressure_events: u64,
}

impl Default for RelayPoolHealth {
    fn default() -> Self {
        Self {
            idle_streams: 0,
            busy_streams: 0,
            evictions: 0,
            idle_timeout: Duration::from_secs(30),
            backpressure_events: 0,
        }
    }
}
