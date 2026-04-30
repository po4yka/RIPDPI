use std::time::{Duration, Instant};

use crate::client::MasqueClientInner;

const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Default)]
pub(crate) struct QuicMigrationSnapshot {
    pub(crate) status: Option<String>,
    pub(crate) reason: Option<String>,
    pub(crate) cooldown_until: Option<Instant>,
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
