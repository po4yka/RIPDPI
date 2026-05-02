#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsExchangeSuccess {
    pub response_bytes: Vec<u8>,
    pub endpoint_label: String,
    pub latency_ms: u64,
}
