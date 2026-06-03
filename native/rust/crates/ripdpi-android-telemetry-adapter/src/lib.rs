#![forbid(unsafe_code)]

mod adaptive;
mod autolearn;
mod direct_path;
mod events;
mod lifecycle;
mod observer;
mod routing;
mod snapshot;
mod state;
#[cfg(test)]
mod tests;
mod types;
mod util;

pub use observer::ProxyTelemetryObserver;
pub use state::ProxyTelemetryState;

pub fn install_recorder() {
    ripdpi_telemetry::recorder::install();
}
