use std::io;
use std::sync::{Mutex, MutexGuard, TryLockError};
use std::time::{Duration, Instant};

use crate::client::ClientInner;
use crate::endpoint::build_client_udp_socket;

const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

pub(crate) struct QuicMigration {
    state: Mutex<QuicMigrationState>,
}

struct QuicMigrationState {
    status: Option<String>,
    reason: Option<String>,
    validated: bool,
    cooldown_until: Option<Instant>,
    current_socket: std::net::UdpSocket,
    previous_socket: Option<std::net::UdpSocket>,
}

impl QuicMigration {
    pub(crate) fn new_not_attempted(current_socket: std::net::UdpSocket) -> Self {
        Self {
            state: Mutex::new(QuicMigrationState {
                status: Some("not_attempted".to_string()),
                reason: None,
                validated: false,
                cooldown_until: None,
                current_socket,
                previous_socket: None,
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, QuicMigrationState> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn snapshot(&self) -> (Option<String>, Option<String>) {
        match self.state.try_lock() {
            Ok(state) => (state.status.clone(), state.reason.clone()),
            Err(TryLockError::Poisoned(poisoned)) => {
                let state = poisoned.into_inner();
                (state.status.clone(), state.reason.clone())
            }
            Err(TryLockError::WouldBlock) => (Some("not_attempted".to_string()), None),
        }
    }
}

type CompleteAction<'a> = Box<dyn FnOnce(&str) + Send + 'a>;
type RollbackAction<'a> = Box<dyn FnOnce(&str) -> io::Result<()> + Send + 'a>;

pub(crate) struct MigrationAttempt<'a> {
    complete: Option<CompleteAction<'a>>,
    rollback: Option<RollbackAction<'a>>,
}

impl<'a> MigrationAttempt<'a> {
    fn new<C, R>(complete: C, rollback: R) -> Self
    where
        C: FnOnce(&str) + Send + 'a,
        R: FnOnce(&str) -> io::Result<()> + Send + 'a,
    {
        Self { complete: Some(Box::new(complete)), rollback: Some(Box::new(rollback)) }
    }

    pub(crate) fn complete(mut self, reason: &str) {
        self.rollback = None;
        if let Some(complete) = self.complete.take() {
            complete(reason);
        }
    }

    pub(crate) fn rollback(mut self, reason: &str) -> io::Result<()> {
        self.complete = None;
        self.rollback.take().map_or(Ok(()), |rollback| rollback(reason))
    }
}

impl Drop for MigrationAttempt<'_> {
    fn drop(&mut self) {
        if let Some(rollback) = self.rollback.take() {
            let _ = rollback("operation_cancelled_after_rebind");
        }
    }
}

impl ClientInner {
    pub(crate) fn begin_quic_migration(&self) -> io::Result<Option<MigrationAttempt<'_>>> {
        let mut state = self.migration.lock();
        if !self.should_attempt_quic_migration(&state) {
            return Ok(None);
        }

        let old_socket = state.current_socket.try_clone()?;
        let new_socket = build_client_udp_socket(self.socket_spec)?;
        let new_socket_clone = new_socket.try_clone()?;
        match self.endpoint.rebind(new_socket) {
            Ok(()) => {
                state.current_socket = new_socket_clone;
                state.status = Some("not_attempted".to_string());
                state.reason = Some("path_challenge_pending".to_string());
                state.previous_socket = Some(old_socket);
                drop(state);
                Ok(Some(MigrationAttempt::new(
                    move |reason| self.complete_quic_migration(reason),
                    move |reason| {
                        let result = self.rollback_quic_migration(reason);
                        if result.is_err() {
                            self.mark_migration_failed("rollback_failed");
                        }
                        result
                    },
                )))
            }
            Err(error) => {
                state.status = Some("failed".to_string());
                state.reason = Some("endpoint_rebind_failed".to_string());
                state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
                Err(error)
            }
        }
    }

    fn complete_quic_migration(&self, reason: &str) {
        let mut state = self.migration.lock();
        if state.previous_socket.is_some() || state.validated {
            state.status = Some("validated".to_string());
            state.reason = Some(reason.to_string());
            state.validated = true;
            state.previous_socket = None;
        }
    }

    fn rollback_quic_migration(&self, reason: &str) -> io::Result<()> {
        let mut state = self.migration.lock();
        let Some(previous_socket) = state.previous_socket.as_ref() else {
            return Ok(());
        };
        let rollback_socket = previous_socket.try_clone()?;
        let replacement = previous_socket.try_clone()?;
        self.endpoint.rebind(rollback_socket)?;
        state.current_socket = replacement;
        state.previous_socket = None;
        state.status = Some("reverted".to_string());
        state.reason = Some(reason.to_string());
        state.validated = false;
        state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
        Ok(())
    }

    fn mark_migration_failed(&self, reason: &str) {
        let mut state = self.migration.lock();
        state.status = Some("failed".to_string());
        state.reason = Some(reason.to_string());
        state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
    }

    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.snapshot()
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;

    #[test]
    fn dropping_attempt_rolls_back_exactly_once() {
        let rollbacks = Arc::new(AtomicUsize::new(0));
        let captured = Arc::clone(&rollbacks);
        let attempt = MigrationAttempt::new(
            |_| {},
            move |_| {
                captured.fetch_add(1, Ordering::Relaxed);
                Ok(())
            },
        );

        drop(attempt);

        assert_eq!(rollbacks.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn completing_attempt_disarms_rollback() {
        let completions = Arc::new(AtomicUsize::new(0));
        let rollbacks = Arc::new(AtomicUsize::new(0));
        let captured_completions = Arc::clone(&completions);
        let captured_rollbacks = Arc::clone(&rollbacks);
        let attempt = MigrationAttempt::new(
            move |_| {
                captured_completions.fetch_add(1, Ordering::Relaxed);
            },
            move |_| {
                captured_rollbacks.fetch_add(1, Ordering::Relaxed);
                Ok(())
            },
        );

        attempt.complete("validated");

        assert_eq!(completions.load(Ordering::Relaxed), 1);
        assert_eq!(rollbacks.load(Ordering::Relaxed), 0);
    }
}
