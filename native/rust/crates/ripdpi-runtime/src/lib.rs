use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, OnceLock};

pub mod platform;
pub mod process;
pub mod runtime;
pub mod runtime_policy;

pub trait RuntimeTelemetrySink: Send + Sync {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize);

    fn on_listener_stopped(&self);

    fn on_client_accepted(&self);

    fn on_client_finished(&self);

    fn on_client_error(&self, error: &io::Error);

    fn on_route_selected(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        phase: &'static str,
    );

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    );
}

static TELEMETRY_SINK: OnceLock<Mutex<Option<Arc<dyn RuntimeTelemetrySink>>>> = OnceLock::new();

fn telemetry_slot() -> &'static Mutex<Option<Arc<dyn RuntimeTelemetrySink>>> {
    TELEMETRY_SINK.get_or_init(|| Mutex::new(None))
}

pub fn install_runtime_telemetry(sink: Arc<dyn RuntimeTelemetrySink>) {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = Some(sink);
    }
}

pub fn clear_runtime_telemetry() {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = None;
    }
}

pub(crate) fn current_runtime_telemetry() -> Option<Arc<dyn RuntimeTelemetrySink>> {
    telemetry_slot().lock().ok().and_then(|slot| slot.clone())
}
