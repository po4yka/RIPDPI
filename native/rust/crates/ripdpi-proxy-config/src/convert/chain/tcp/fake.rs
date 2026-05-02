use ripdpi_config::{FakeOrder, FakeSeqMode};

use crate::types::ProxyConfigError;

pub(crate) fn parse_fake_order(value: &str) -> Result<FakeOrder, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" | "0" => Ok(FakeOrder::BeforeEach),
        "1" => Ok(FakeOrder::AllFakesFirst),
        "2" => Ok(FakeOrder::RealFakeRealFake),
        "3" => Ok(FakeOrder::AllRealsFirst),
        _ => Err(ProxyConfigError::InvalidConfig("tcpChainSteps fakeOrder must be 0, 1, 2, 3, or empty".to_string())),
    }
}

pub(crate) fn parse_fake_seq_mode(value: &str) -> Result<FakeSeqMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" | "duplicate" => Ok(FakeSeqMode::Duplicate),
        "sequential" => Ok(FakeSeqMode::Sequential),
        _ => Err(ProxyConfigError::InvalidConfig(
            "tcpChainSteps fakeSeqMode must be duplicate, sequential, or empty".to_string(),
        )),
    }
}
