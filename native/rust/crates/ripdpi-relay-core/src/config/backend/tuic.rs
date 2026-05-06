#[derive(Debug, Clone, Default)]
pub struct TuicRelayConfig {
    pub uuid: Option<String>,
    pub password: Option<String>,
    pub zero_rtt: bool,
    pub congestion_control: String,
}
