use serde::{Deserialize, Serialize};

use super::defaults::telegram_transfer_status_error;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramVerdict {
    Ok,
    Slow,
    Partial,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramTransferStatus {
    Ok,
    Slow,
    Stalled,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TelegramObservationFact {
    pub verdict: TelegramVerdict,
    #[serde(default)]
    pub quality_score: i32,
    #[serde(default = "telegram_transfer_status_error")]
    pub download_status: TelegramTransferStatus,
    #[serde(default = "telegram_transfer_status_error")]
    pub upload_status: TelegramTransferStatus,
    #[serde(default)]
    pub dc_reachable: usize,
    #[serde(default)]
    pub dc_total: usize,
}
