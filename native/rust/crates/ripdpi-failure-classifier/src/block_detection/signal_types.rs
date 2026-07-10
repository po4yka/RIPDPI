use serde::{Deserialize, Serialize};

use crate::FailureEvidenceContext;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockSignal {
    HttpBlockpage,
    HttpRedirect,
    TlsAlert,
    SilentDrop,
    TcpReset,
    ConnectionFreeze,
    QuicBreakage,
    TcpRetransmissions,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlockSignalObservation {
    pub signal: BlockSignal,
    pub provider: Option<String>,
    pub context: FailureEvidenceContext,
}

impl BlockSignal {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HttpBlockpage => "http_blockpage",
            Self::HttpRedirect => "http_redirect",
            Self::TlsAlert => "tls_alert",
            Self::SilentDrop => "silent_drop",
            Self::TcpReset => "tcp_reset",
            Self::ConnectionFreeze => "connection_freeze",
            Self::QuicBreakage => "quic_breakage",
            Self::TcpRetransmissions => "tcp_retransmissions",
        }
    }
}
