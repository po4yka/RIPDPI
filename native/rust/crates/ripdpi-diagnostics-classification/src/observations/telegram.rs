use crate::types::{ObservationKind, ProbeObservation, ProbeResult, TelegramObservationFact};

use super::common::{base_observation, detail_value, telegram_transfer_status, telegram_verdict};

const PROBE_TYPE: &str = "telegram_availability";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_telegram_observation(result))
}

pub(crate) fn build_telegram_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Telegram);
    observation.telegram = Some(TelegramObservationFact {
        verdict: telegram_verdict(detail_value(result, "verdict").unwrap_or(result.outcome.as_str())),
        quality_score: detail_value(result, "qualityScore")
            .and_then(|value| value.parse::<i32>().ok())
            .unwrap_or_default(),
        download_status: telegram_transfer_status(detail_value(result, "downloadStatus").unwrap_or("error")),
        upload_status: telegram_transfer_status(detail_value(result, "uploadStatus").unwrap_or("error")),
        dc_reachable: detail_value(result, "dcReachable").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0),
        dc_total: detail_value(result, "dcTotal").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0),
    });
    observation
}
