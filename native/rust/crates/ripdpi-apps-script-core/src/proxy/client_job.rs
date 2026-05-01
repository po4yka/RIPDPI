use std::collections::HashMap;
use std::io;
use std::sync::Arc;

use tokio::net::TcpStream;
use tokio::sync::Mutex;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::socks_session;
use crate::telemetry::SharedTelemetryState;

pub(crate) async fn handle(
    stream: TcpStream,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: HashMap<String, String>,
) -> io::Result<()> {
    socks_session::handle(stream, relay, mitm, telemetry, hosts).await
}
