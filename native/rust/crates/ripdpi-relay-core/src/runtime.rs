use std::io;
mod events;
mod listener;
mod session;
mod state;
mod telemetry;

use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;

use ripdpi_xhttp::XhttpSocketProtector;

use crate::backend::RelayBackend;
use crate::backend::builder::{build_backend, build_backend_with_socket_protector};
use crate::config::ResolvedRelayRuntimeConfig;
use crate::runtime::events::{emit_runtime_ready, emit_runtime_stopped};
use crate::runtime::listener::run_accept_loop;
use crate::runtime::state::{RuntimeState, SessionDrainOutcome};
use crate::runtime_validation::validate_runtime_config;
use crate::socks::SocksTelemetry;
use crate::telemetry::{RelayTelemetry, TcpConnectObservation};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

/// Bounds accepted client sockets and tracked tasks for the full SOCKS session lifetime.
const MAX_CONCURRENT_SOCKS_SESSIONS: usize = 256;

/// Bounded grace window for draining in-flight SOCKS5 sessions on shutdown.
/// Matches the 5 s used by `ripdpi-tunnel-core`'s UDP-association shutdown so
/// the relay's stop path has the same deterministic upper bound. After the
/// shutdown token is cancelled sessions normally unwind in well under this; the
/// timeout only caps a pathological stuck session.
const SESSION_DRAIN_GRACE: Duration = Duration::from_secs(5);

pub struct RelayRuntime {
    config: ResolvedRelayRuntimeConfig,
    socket_protector: Option<XhttpSocketProtector>,
    state: RuntimeState,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self { config, socket_protector: None, state: RuntimeState::new() })
    }

    pub fn with_socket_protector<P>(config: ResolvedRelayRuntimeConfig, protector: P) -> Arc<Self>
    where
        P: Fn(std::os::fd::RawFd) -> io::Result<()> + Send + Sync + 'static,
    {
        Arc::new(Self {
            config,
            socket_protector: Some(XhttpSocketProtector::new(protector)),
            state: RuntimeState::new(),
        })
    }

    pub fn stop(&self) {
        self.state.request_stop();
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        telemetry::build_telemetry(self)
    }

    pub(super) fn profile_catalog_validated(&self) -> bool {
        ripdpi_xhttp::catalog_validated_tls_profile(
            &self.config.common.tls_fingerprint_profile,
            &self.config.common.server_name,
        )
    }

    pub(super) fn confirm_good_dpi_eligible(&self) -> bool {
        matches!(
            crate::config::RelayKind::from_config(&self.config),
            crate::config::RelayKind::VlessReality { xhttp: false }
        ) && self.profile_catalog_validated()
    }

    /// Install a quality observer invoked for every upstream TCP connect
    /// attempt. Replaces any previously installed observer. Delegates to
    /// `RuntimeState::set_quality_observer`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_quality_observer(&self, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
        self.state.set_quality_observer(observer);
    }

    /// Install a readiness observer fired exactly once when the listener is
    /// bound and the relay is about to serve (immediately after the
    /// `runtime_ready` event). The adapter layer wires this to a native
    /// readiness push so Kotlin no longer polls telemetry (see ADR 0003);
    /// install it before [`RelayRuntime::run`] starts. Delegates to
    /// `RuntimeState::set_readiness_observer`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) {
        self.state.set_readiness_observer(observer);
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        if self.state.stop_requested() {
            // A stop requested before `run` wins before any lifecycle side
            // effect: no backend build, no listener bind, and no transient
            // ready-then-stopped event pair.
            return Ok(());
        }
        let backend = match self.socket_protector.clone() {
            Some(protector) => Arc::new(build_backend_with_socket_protector(&self.config, Some(protector)).await?),
            None => Arc::new(build_backend(&self.config).await?),
        };
        validate_runtime_config(&self.config, &backend)?;
        // Fail closed on a backend that cannot serve in-process (`off`, an
        // unknown kind, or a subprocess-only kind wired to the native runtime).
        // Binding the listener and emitting `runtime_ready` here would report
        // a working relay to Kotlin while every CONNECT failed per session.
        if let RelayBackend::Unsupported { kind } = backend.as_ref() {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!(
                    "relay backend {kind} cannot serve in-process; refusing to bind a listener that would fail every session"
                ),
            ));
        }
        self.state.set_backend(Arc::clone(&backend))?;

        let bind_addr = format!("{}:{}", self.config.common.local_socks_host, self.config.common.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        let listener_address = listener.local_addr()?.to_string();
        self.state.set_listener_address(listener_address.clone())?;
        self.state.set_running(true);
        emit_runtime_ready(&listener_address);
        // Push readiness to any installed observer (native readiness event,
        // ADR 0003) at the same point the `runtime_ready` telemetry fires, so
        // the Kotlin wrapper need not poll. No-op when no observer is set.
        self.state.notify_ready();

        run_accept_loop(Arc::clone(&self), backend, listener, MAX_CONCURRENT_SOCKS_SESSIONS, ACCEPT_POLL_INTERVAL)
            .await;

        // The accept loop exited because `stop()` set `stop_requested` and
        // cancelled the shutdown token. Drain in-flight sessions within a
        // bounded window so shutdown is deterministic and no session leaks its
        // upstream connection until the runtime is dropped.
        match self.state.drain_sessions(SESSION_DRAIN_GRACE).await {
            SessionDrainOutcome::Graceful => {}
            SessionDrainOutcome::Aborted => {
                self.state.record_listener_error(
                    "relay session drain exceeded grace window; remaining tasks aborted".to_string(),
                );
            }
            SessionDrainOutcome::AbortTimedOut => {
                self.state.set_running(false);
                emit_runtime_stopped();
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "relay session tasks did not terminate after forced abort",
                ));
            }
        }

        self.state.set_running(false);
        emit_runtime_stopped();
        Ok(())
    }
}

impl SocksTelemetry for RelayRuntime {
    fn next_attempt_id(&self) -> u64 {
        self.state.next_attempt_id()
    }

    fn record_target(&self, target: String) {
        self.state.record_target(target);
    }

    fn record_handshake_error(&self, error: String) {
        self.state.record_handshake_error(error);
    }

    fn record_xudp_association_opened(&self) {
        self.state.record_xudp_association_opened();
    }

    fn record_xudp_association_closed(&self, reason: &'static str) {
        self.state.record_xudp_association_closed(reason);
    }

    fn record_xudp_uplink(&self, bytes: usize, queue_high_water_mark: usize) {
        self.state.record_xudp_uplink(bytes, queue_high_water_mark);
    }

    fn record_xudp_downlink(&self, bytes: usize) {
        self.state.record_xudp_downlink(bytes);
    }

    fn record_xudp_open_failure(&self) {
        self.state.record_xudp_open_failure();
    }

    fn record_xudp_write_failure(&self, timed_out: bool) {
        self.state.record_xudp_write_failure(timed_out);
    }

    fn record_xudp_read_failure(&self, timed_out: bool) {
        self.state.record_xudp_read_failure(timed_out);
    }

    fn emit_connect_observation(&self, obs: TcpConnectObservation) {
        self.state.emit_connect_observation(obs);
    }

    fn record_confirm_good_passive_stall(
        &self,
        target: &str,
        application_bytes_sent: u64,
        application_response_bytes: u64,
        profile_catalog_validated: bool,
    ) {
        self.state.record_confirm_good_passive_stall(
            target,
            application_bytes_sent,
            application_response_bytes,
            profile_catalog_validated,
        );
    }
}
