#![forbid(unsafe_code)]

mod config;
mod domain_fronter;
mod mitm;
mod proxy;
mod socks5;
mod telemetry;

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

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

    /// Serve until [`Self::stop`] is called, also when it was called before this call.
    ///
    /// # Cancel safety
    /// cancel-safe: dropping the future closes the listener and its client tasks.
    pub async fn run(self: &Arc<Self>) -> io::Result<()> {
        // A runtime serves one start, so the flag is never reset: a stop() that
        // races startup is kept. Ordering: it only carries the stop bit; no data
        // is published through it, and the accept loop observes it after an
        // async timeout.
        if self.stop_requested.load(Ordering::Relaxed) {
            self.telemetry.mark_stopped();
            return Ok(());
        }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn stop_that_arrives_before_run_is_not_lost() {
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let data_dir = std::env::temp_dir().join(format!("ripdpi-apps-script-stop-{port}"));
        let config = AppsScriptRuntimeConfig::from_json(&format!(
            r#"{{"scriptIds":["script-id"],"authKey":"secret","listenPort":{port},"dataDir":"{}"}}"#,
            data_dir.display()
        ))
        .unwrap();
        let runtime = RelayRuntime::new(config);

        runtime.stop();
        let result = tokio::time::timeout(std::time::Duration::from_secs(3), runtime.run()).await;
        let _ = std::fs::remove_dir_all(&data_dir);

        assert!(matches!(result, Ok(Ok(()))), "run must honour the earlier stop instead of serving");
    }
}
