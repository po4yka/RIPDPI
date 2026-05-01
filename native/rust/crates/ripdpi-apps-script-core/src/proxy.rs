mod client_job;
mod http_relay;
mod listener;
mod mitm_rewrite;
mod socks_session;
mod tunnel_dispatch;

use std::io;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use tokio::sync::Mutex;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::telemetry::SharedTelemetryState;
use crate::AppsScriptRuntimeConfig;

pub struct ProxyServer {
    config: AppsScriptRuntimeConfig,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
}

impl ProxyServer {
    pub fn new(config: AppsScriptRuntimeConfig, telemetry: SharedTelemetryState) -> io::Result<Self> {
        let mitm = MitmCertManager::new_in(&config.data_dir).map_err(|error| io::Error::other(error.to_string()))?;
        Ok(Self {
            relay: Arc::new(AppsScriptDomainFronter::new(&config)),
            mitm: Arc::new(Mutex::new(mitm)),
            config,
            telemetry,
        })
    }

    pub async fn run(&self, stop_requested: Arc<AtomicBool>) -> io::Result<()> {
        listener::run(&self.config, self.relay.clone(), self.mitm.clone(), self.telemetry.clone(), stop_requested).await
    }
}
