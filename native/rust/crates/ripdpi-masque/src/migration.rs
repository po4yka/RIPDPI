use std::time::{Duration, Instant};

use crate::client::MasqueClientInner;

const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Default)]
pub(crate) struct QuicMigrationSnapshot {
    pub(crate) status: Option<String>,
    pub(crate) reason: Option<String>,
    pub(crate) cooldown_until: Option<Instant>,
}

/// Typed migration status code. Mirrors the documented vocabulary in
/// `CONFORMANCE.md` § "QUIC Migration Telemetry Vocabulary". Coexists with the
/// string-based API in `record_quic_migration_status` for backwards
/// compatibility while callsites migrate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MigrationStatus {
    /// Initial state; no migration or fallback yet.
    NotAttempted,
    /// HTTP/2 classic CONNECT was selected explicitly for TCP.
    Http2Selected,
    /// Client fell back to HTTP/2 after the H3 attempt failed.
    Http2Fallback,
    /// Migration or fallback ultimately failed; cooldown engaged.
    Failed,
    /// Migration attempted then rolled back; cooldown engaged.
    Reverted,
}

impl MigrationStatus {
    /// Stable string representation matching the documented vocabulary.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::NotAttempted => "not_attempted",
            Self::Http2Selected => "http2_selected",
            Self::Http2Fallback => "http2_fallback",
            Self::Failed => "failed",
            Self::Reverted => "reverted",
        }
    }
}

/// Typed reason code that callers attach to a `Http2Fallback` status.
/// Replaces the free-form `http3_connect_failed_<inner>` strings while
/// preserving the documented prefixes so existing telemetry consumers
/// can keep their downstream string-match logic.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum H3FallbackReason {
    /// H3 CONNECT attempt rejected with an inner-error tag from
    /// `classify_attempt_failure`. The `inner` field carries the
    /// classifier's tag.
    H3ConnectFailedInner { inner: String },
    /// H3 CONNECT attempt exceeded the per-attempt timeout.
    H3ConnectTimedOut,
    /// Generic H3 CONNECT failure when no inner classification is
    /// available.
    H3ConnectFailed,
}

impl H3FallbackReason {
    /// Stable string representation matching the documented vocabulary.
    /// `H3ConnectFailedInner { inner }` renders as
    /// `"http3_connect_failed_<inner>"`.
    pub fn as_string(&self) -> String {
        match self {
            Self::H3ConnectFailedInner { inner } => format!("http3_connect_failed_{inner}"),
            Self::H3ConnectTimedOut => "http3_connect_timed_out".to_string(),
            Self::H3ConnectFailed => "http3_connect_failed".to_string(),
        }
    }
}

impl MasqueClientInner {
    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.last_migration_snapshot.try_lock().map_or_else(
            |_| (Some("not_attempted".to_string()), None),
            |snapshot| (snapshot.status.clone(), snapshot.reason.clone()),
        )
    }

    pub(crate) async fn record_quic_migration_status(&self, status: &str, reason: Option<&str>) {
        let mut snapshot = self.last_migration_snapshot.lock().await;
        snapshot.status = Some(status.to_string());
        snapshot.reason = reason.map(ToOwned::to_owned);
        if status == "reverted" || status == "failed" {
            snapshot.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
        } else {
            snapshot.cooldown_until = None;
        }
    }
}

#[cfg(test)]
mod typed_status_tests {
    use super::*;

    #[test]
    fn migration_status_as_str_matches_documented_vocabulary() {
        // String values must match CONFORMANCE.md
        // § QUIC Migration Telemetry Vocabulary exactly.
        assert_eq!(MigrationStatus::NotAttempted.as_str(), "not_attempted");
        assert_eq!(MigrationStatus::Http2Selected.as_str(), "http2_selected");
        assert_eq!(MigrationStatus::Http2Fallback.as_str(), "http2_fallback");
        assert_eq!(MigrationStatus::Failed.as_str(), "failed");
        assert_eq!(MigrationStatus::Reverted.as_str(), "reverted");
    }

    #[test]
    fn h3_fallback_reason_inner_renders_with_documented_prefix() {
        let reason = H3FallbackReason::H3ConnectFailedInner { inner: "connect".to_string() };
        assert_eq!(reason.as_string(), "http3_connect_failed_connect");
    }

    #[test]
    fn h3_fallback_reason_timed_out_matches_documented_string() {
        assert_eq!(H3FallbackReason::H3ConnectTimedOut.as_string(), "http3_connect_timed_out");
    }

    #[test]
    fn h3_fallback_reason_generic_matches_documented_string() {
        assert_eq!(H3FallbackReason::H3ConnectFailed.as_string(), "http3_connect_failed");
    }

    #[test]
    fn h3_fallback_reason_round_trip_for_existing_test_pair() {
        // The existing quic_migration_snapshot_records_http2_fallback_reason
        // test exercises the ("http2_fallback", "http3_connect_failed_connect")
        // pair. Typed enums must reproduce the exact same wire strings.
        let status = MigrationStatus::Http2Fallback;
        let reason = H3FallbackReason::H3ConnectFailedInner { inner: "connect".to_string() };
        assert_eq!(status.as_str(), "http2_fallback");
        assert_eq!(reason.as_string(), "http3_connect_failed_connect");
    }
}
