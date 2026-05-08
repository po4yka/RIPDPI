mod accept_loop;
mod client_job;
mod worker_pool;

use std::io;
use std::net::TcpListener;
use std::sync::Arc as StdArc;

use ripdpi_proxy_runtime_adapter::model::config::{ensure_default_ttl, ListenerSettings, RuntimeConfig};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use self::accept_loop::run_accept_loop;
use super::state::RuntimeState;

pub(super) fn build_listener(settings: ListenerSettings) -> io::Result<TcpListener> {
    listener_platform::bind_tcp_listener(settings.bind_addr)
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    ensure_default_ttl(&mut config, listener_platform::detect_default_ttl)?;
    let state = RuntimeState::new(config, control.clone());
    let settings = state.listener_settings;
    let listener_addr = listener.local_addr()?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(listener_addr, settings.client_capacity, settings.route_group_count);
    }
    // Drain any autolearn events accumulated during policy load so that
    // telemetry reflects the initial state before any connections arrive.
    // The policy port's ServicesState::drop handles persistence on shutdown.
    state.drain_autolearn_events();

    super::warmup::spawn_warmup_thread(state.clone());

    // Check for network identity changes and trigger a lightweight reprobe
    // if the network switched (e.g. WiFi -> cellular).
    super::reprobe::maybe_spawn_reprobe(&state);

    run_accept_loop(listener, state, control, settings.client_capacity)
}
