use std::sync::{Mutex, MutexGuard, TryLockError};
use std::time::{Duration, Instant};

use crate::client::ClientInner;
use crate::error::Result;
use crate::tls_quic::{build_client_udp_socket, rebind_endpoint};

const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

pub(crate) struct QuicMigration {
    state: Mutex<QuicMigrationState>,
}

struct QuicMigrationState {
    status: Option<String>,
    reason: Option<String>,
    validated: bool,
    cooldown_until: Option<Instant>,
    current_socket: Option<std::net::UdpSocket>,
    previous_socket: Option<std::net::UdpSocket>,
}

impl QuicMigration {
    pub(crate) fn new_not_attempted(current_socket: Option<std::net::UdpSocket>) -> Self {
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
type RollbackAction<'a> = Box<dyn FnOnce(&str) -> Result<()> + Send + 'a>;

pub(crate) struct MigrationAttempt<'a> {
    complete: Option<CompleteAction<'a>>,
    rollback: Option<RollbackAction<'a>>,
}

impl<'a> MigrationAttempt<'a> {
    fn new<C, R>(complete: C, rollback: R) -> Self
    where
        C: FnOnce(&str) + Send + 'a,
        R: FnOnce(&str) -> Result<()> + Send + 'a,
    {
        Self { complete: Some(Box::new(complete)), rollback: Some(Box::new(rollback)) }
    }

    pub(crate) fn complete(mut self, reason: &str) {
        self.rollback = None;
        if let Some(complete) = self.complete.take() {
            complete(reason);
        }
    }

    pub(crate) fn rollback(mut self, reason: &str) -> Result<()> {
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
    /// Rebind to a fresh carrier socket when a migration is due. Migration is
    /// an optional optimisation: any setup failure (protect callback gone,
    /// fd exhaustion, rebind error) is recorded with a cooldown and the caller
    /// keeps using the healthy current path.
    pub(crate) fn begin_quic_migration(&self) -> Option<MigrationAttempt<'_>> {
        let mut state = self.migration.lock();
        if !self.should_attempt_quic_migration(&state) {
            return None;
        }

        let sockets = (|| -> std::io::Result<_> {
            let old_socket = if let Some(hopping) = &self.hopping_socket {
                hopping.current_socket()?
            } else {
                state
                    .current_socket
                    .as_ref()
                    .ok_or_else(|| std::io::Error::other("missing QUIC migration socket"))?
                    .try_clone()?
            };
            let new_socket = build_client_udp_socket(&self.socket_spec)?;
            let new_socket_clone = new_socket.try_clone()?;
            Ok((old_socket, new_socket, new_socket_clone))
        })();
        let (old_socket, new_socket, new_socket_clone) = match sockets {
            Ok(sockets) => sockets,
            Err(error) => {
                tracing::debug!(error = %error, "Hysteria QUIC migration skipped: no replacement socket");
                Self::record_failure(&mut state, "migration_socket_failed");
                return None;
            }
        };
        match rebind_endpoint(&self.endpoint, &self.socket_spec, new_socket, self.hopping_socket.as_deref()) {
            Ok(()) => {
                state.current_socket = self.hopping_socket.is_none().then_some(new_socket_clone);
                state.status = Some("not_attempted".to_string());
                state.reason = Some("path_challenge_pending".to_string());
                state.previous_socket = Some(old_socket);
                drop(state);
                Some(MigrationAttempt::new(
                    move |reason| self.complete_quic_migration(reason),
                    move |reason| {
                        let result = self.rollback_quic_migration(reason);
                        if result.is_err() {
                            self.mark_migration_failed("rollback_failed");
                        }
                        result
                    },
                ))
            }
            Err(error) => {
                tracing::debug!(error = %error, "Hysteria QUIC migration skipped: endpoint rebind failed");
                Self::record_failure(&mut state, "endpoint_rebind_failed");
                None
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

    fn rollback_quic_migration(&self, reason: &str) -> Result<()> {
        let mut state = self.migration.lock();
        let Some(previous_socket) = state.previous_socket.as_ref() else {
            return Ok(());
        };
        let rollback_socket = previous_socket.try_clone()?;
        let replacement = previous_socket.try_clone()?;
        rebind_endpoint(&self.endpoint, &self.socket_spec, rollback_socket, self.hopping_socket.as_deref())?;
        state.current_socket = self.hopping_socket.is_none().then_some(replacement);
        state.previous_socket = None;
        state.status = Some("reverted".to_string());
        state.reason = Some(reason.to_string());
        state.validated = false;
        state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
        Ok(())
    }

    fn mark_migration_failed(&self, reason: &str) {
        Self::record_failure(&mut self.migration.lock(), reason);
    }

    fn record_failure(state: &mut QuicMigrationState, reason: &str) {
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

    /// Accepts the first protect call (the connection's carrier) and rejects
    /// every later one, like a protect callback that fails mid-session.
    struct RejectAfterFirst(AtomicUsize);

    impl ripdpi_native_protect::ProtectCallback for RejectAfterFirst {
        fn protect(&self, _fd: std::os::fd::RawFd) -> std::io::Result<()> {
            if self.0.fetch_add(1, Ordering::SeqCst) == 0 {
                Ok(())
            } else {
                Err(std::io::Error::new(std::io::ErrorKind::NotConnected, "protect callback gone"))
            }
        }
    }

    #[test]
    fn migration_setup_failure_keeps_the_healthy_path_and_arms_the_cooldown() {
        let _lock = crate::quic_transport::endpoint::protect_tests::PROTECT_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let callback = Arc::new(RejectAfterFirst(AtomicUsize::new(0)));
        ripdpi_native_protect::register_protect_callback(callback.clone());
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime");
        // cancel-safe: the local runtime owns every client and server task.
        let outcome = runtime.block_on(async {
            let server = crate::test_server::TestServer::start(crate::test_server::ServerOptions::default())
                .expect("test server");
            let mut config = server.client_config();
            config.socket_protection = ripdpi_native_protect::SocketProtectionPolicy::VpnRequired;
            config.quic_migrate_after_handshake = true;
            let client = crate::connect(&config).await.expect("connect on a protected carrier");
            let first = client.tcp_connect("192.0.2.1:80").await.map(drop);
            let second = client.tcp_connect("192.0.2.1:80").await.map(drop);
            (first, second, client.quic_migration_snapshot())
        });
        ripdpi_native_protect::unregister_protect_callback();

        let (first, second, (status, reason)) = outcome;
        assert!(first.is_ok(), "a failed optional migration must not fail the stream: {first:?}");
        assert!(second.is_ok(), "the next stream must also use the healthy path: {second:?}");
        assert_eq!(status.as_deref(), Some("failed"));
        assert_eq!(reason.as_deref(), Some("migration_socket_failed"));
        assert_eq!(callback.0.load(Ordering::SeqCst), 2, "the cooldown must stop a second migration attempt");
    }

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
