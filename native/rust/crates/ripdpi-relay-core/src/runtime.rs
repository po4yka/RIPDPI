use std::io;
mod events;
mod listener;
mod session;
mod state;
mod telemetry;

use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;

use crate::backend::build_backend;
use crate::config::ResolvedRelayRuntimeConfig;
use crate::runtime::events::{emit_runtime_ready, emit_runtime_stopped};
use crate::runtime::listener::run_accept_loop;
use crate::runtime::state::RuntimeState;
use crate::runtime_validation::validate_runtime_config;
use crate::socks::SocksTelemetry;
use crate::telemetry::{RelayTelemetry, TcpConnectObservation};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

pub struct RelayRuntime {
    config: ResolvedRelayRuntimeConfig,
    state: RuntimeState,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self { config, state: RuntimeState::new() })
    }

    pub fn stop(&self) {
        self.state.request_stop();
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        telemetry::build_telemetry(self)
    }

    /// Install a quality observer invoked for every upstream TCP connect
    /// attempt. Replaces any previously installed observer. Delegates to
    /// `RuntimeState::set_quality_observer`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_quality_observer(&self, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
        self.state.set_quality_observer(observer);
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        let backend = Arc::new(build_backend(&self.config).await?);
        validate_runtime_config(&self.config, &backend)?;
        self.state.set_backend(Arc::clone(&backend))?;

        let bind_addr = format!("{}:{}", self.config.common.local_socks_host, self.config.common.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        self.state.set_listener_address(bind_addr.clone())?;
        self.state.set_running(true);
        emit_runtime_ready(&bind_addr);

        run_accept_loop(Arc::clone(&self), backend, listener, ACCEPT_POLL_INTERVAL).await;

        self.state.set_running(false);
        emit_runtime_stopped();
        Ok(())
    }
}

impl SocksTelemetry for RelayRuntime {
    fn record_target(&self, target: String) {
        self.state.record_target(target);
    }

    fn record_handshake_error(&self, error: String) {
        self.state.record_handshake_error(error);
    }

    fn emit_connect_observation(&self, obs: TcpConnectObservation) {
        self.state.emit_connect_observation(obs);
    }
}
