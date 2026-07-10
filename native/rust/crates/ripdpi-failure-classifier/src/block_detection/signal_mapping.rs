use crate::{ClassifiedFailure, FailureClass, FailureEvidenceContext};

use super::signal_types::{BlockSignal, BlockSignalObservation};

pub fn block_signal_from_failure(
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) -> Option<BlockSignalObservation> {
    block_signal_from_failure_with_context(failure, tcp_total_retransmissions, FailureEvidenceContext::default())
}

pub fn block_signal_from_failure_with_context(
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
    context: FailureEvidenceContext,
) -> Option<BlockSignalObservation> {
    let provider = failure_tag(failure, "provider").map(ToOwned::to_owned);
    let signal = match failure.class {
        FailureClass::HttpBlockpage => BlockSignal::HttpBlockpage,
        FailureClass::Redirect => BlockSignal::HttpRedirect,
        FailureClass::TlsAlert => BlockSignal::TlsAlert,
        FailureClass::SilentDrop => {
            if tcp_total_retransmissions.unwrap_or_default() >= 3 {
                BlockSignal::TcpRetransmissions
            } else {
                BlockSignal::SilentDrop
            }
        }
        FailureClass::TcpReset => BlockSignal::TcpReset,
        FailureClass::ConnectionFreeze => BlockSignal::ConnectionFreeze,
        FailureClass::QuicBreakage => BlockSignal::QuicBreakage,
        _ => return None,
    };
    Some(BlockSignalObservation { signal, provider, context })
}

fn failure_tag<'a>(failure: &'a ClassifiedFailure, key: &str) -> Option<&'a str> {
    failure.evidence.tags.iter().find_map(|tag| tag.strip_prefix(key).and_then(|value| value.strip_prefix('=')))
}
