use crate::types::scan::TransportFailureKind;

use super::{
    EndpointProbeStatus, HttpProbeStatus, QuicProbeStatus, StrategyProbeProtocol, StrategyProbeStatus,
    TelegramTransferStatus, TlsProbeStatus,
};

pub(crate) fn transport_failure_none() -> TransportFailureKind {
    TransportFailureKind::None
}

pub(crate) fn http_probe_status_not_run() -> HttpProbeStatus {
    HttpProbeStatus::NotRun
}

pub(crate) fn tls_probe_status_not_run() -> TlsProbeStatus {
    TlsProbeStatus::NotRun
}

pub(crate) fn quic_probe_status_not_run() -> QuicProbeStatus {
    QuicProbeStatus::NotRun
}

pub(crate) fn endpoint_probe_status_not_run() -> EndpointProbeStatus {
    EndpointProbeStatus::NotRun
}

pub(crate) fn telegram_transfer_status_error() -> TelegramTransferStatus {
    TelegramTransferStatus::Error
}

pub(crate) fn strategy_probe_protocol_candidate() -> StrategyProbeProtocol {
    StrategyProbeProtocol::Candidate
}

pub(crate) fn strategy_probe_status_failed() -> StrategyProbeStatus {
    StrategyProbeStatus::Failed
}
