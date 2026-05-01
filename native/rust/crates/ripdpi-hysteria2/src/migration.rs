use std::time::{Duration, Instant};

use crate::client::ClientInner;
use crate::error::Result;
use crate::tls_quic::{build_client_udp_socket, rebind_endpoint};

const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Default)]
pub(crate) struct QuicMigrationState {
    status: Option<String>,
    reason: Option<String>,
    validated: bool,
    cooldown_until: Option<Instant>,
    previous_socket: Option<std::net::UdpSocket>,
}

impl QuicMigrationState {
    pub(crate) fn new_not_attempted() -> Self {
        Self { status: Some("not_attempted".to_string()), ..Self::default() }
    }
}

impl ClientInner {
    pub(crate) async fn begin_quic_migration(&self) -> Result<bool> {
        let should_attempt = {
            let state = self.migration.lock().await;
            self.should_attempt_quic_migration(&state)
        };
        if !should_attempt {
            return Ok(false);
        }

        let old_socket = self.current_socket.lock().await.try_clone()?;
        let new_socket = build_client_udp_socket(&self.socket_spec)?;
        let new_socket_clone = new_socket.try_clone()?;
        match rebind_endpoint(&self.endpoint, &self.socket_spec, new_socket) {
            Ok(()) => {
                *self.current_socket.lock().await = new_socket_clone;
                let mut state = self.migration.lock().await;
                state.status = Some("not_attempted".to_string());
                state.reason = Some("path_challenge_pending".to_string());
                state.previous_socket = Some(old_socket);
                Ok(true)
            }
            Err(error) => {
                let mut state = self.migration.lock().await;
                state.status = Some("failed".to_string());
                state.reason = Some("endpoint_rebind_failed".to_string());
                state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
                Err(error.into())
            }
        }
    }

    pub(crate) async fn complete_quic_migration(&self, reason: &str) {
        let mut state = self.migration.lock().await;
        if state.previous_socket.is_some() || state.validated {
            state.status = Some("validated".to_string());
            state.reason = Some(reason.to_string());
            state.validated = true;
            state.previous_socket = None;
        }
    }

    pub(crate) async fn rollback_quic_migration(&self, reason: &str) -> Result<()> {
        let previous_socket = {
            let mut state = self.migration.lock().await;
            let Some(previous_socket) = state.previous_socket.take() else {
                return Ok(());
            };
            previous_socket
        };
        let replacement = previous_socket.try_clone()?;
        rebind_endpoint(&self.endpoint, &self.socket_spec, previous_socket)?;
        *self.current_socket.lock().await = replacement;
        let mut state = self.migration.lock().await;
        state.status = Some("reverted".to_string());
        state.reason = Some(reason.to_string());
        state.validated = false;
        state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
        Ok(())
    }

    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.try_lock().map_or_else(
            |_| (Some("not_attempted".to_string()), None),
            |state| (state.status.clone(), state.reason.clone()),
        )
    }

    fn should_attempt_quic_migration(&self, state: &QuicMigrationState) -> bool {
        if !self.migrate_after_handshake {
            return false;
        }
        if state.validated || state.previous_socket.is_some() {
            return false;
        }
        state.cooldown_until.is_none_or(|cooldown_until| cooldown_until <= Instant::now())
    }
}
