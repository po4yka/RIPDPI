#[derive(Clone, Default)]
pub struct TuicRelayConfig {
    pub uuid: Option<String>,
    pub password: Option<String>,
    pub zero_rtt: bool,
    pub congestion_control: String,
}

impl_redacted_debug!(TuicRelayConfig { zero_rtt, congestion_control });
