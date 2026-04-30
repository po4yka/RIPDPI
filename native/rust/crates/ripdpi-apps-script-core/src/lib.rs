#![forbid(unsafe_code)]

mod config;
mod domain_fronter;
mod mitm;
mod proxy;
mod socks5;
mod telemetry;

use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub use config::AppsScriptRuntimeConfig;
pub use telemetry::RelayTelemetry;

use proxy::ProxyServer;
use telemetry::RelayTelemetryState;

pub struct RelayRuntime {
    config: AppsScriptRuntimeConfig,
    stop_requested: Arc<AtomicBool>,
    telemetry: Arc<RelayTelemetryState>,
}

impl RelayRuntime {
    pub fn new(config: AppsScriptRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            telemetry: Arc::new(RelayTelemetryState::new(&config)),
            stop_requested: Arc::new(AtomicBool::new(false)),
            config,
        })
    }

    pub async fn run(self: &Arc<Self>) -> io::Result<()> {
        // Ordering: this flag only carries the stop bit; no data is published
        // through it, and the accept loop observes it after an async timeout.
        self.stop_requested.store(false, Ordering::Relaxed);
        self.telemetry.mark_starting();
        let server = ProxyServer::new(self.config.clone(), self.telemetry.clone())?;
        let result = server.run(self.stop_requested.clone()).await;
        if let Err(error) = &result {
            self.telemetry.record_error(error.to_string());
        }
        self.telemetry.mark_stopped();
        result
    }

    pub fn stop(&self) {
        self.telemetry.mark_stopping();
        // Ordering: stop_requested is a standalone cancellation bit. The
        // listener does not acquire any memory through it, so Relaxed is enough.
        self.stop_requested.store(true, Ordering::Relaxed);
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        self.telemetry.snapshot(&self.config)
    }
}
