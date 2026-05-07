mod accept_loop;
mod client_job;
mod worker_pool;

use std::io;
use std::net::TcpListener;
use std::sync::Arc as StdArc;

use ripdpi_proxy_runtime_adapter::model::config::{
    client_capacity, ensure_default_ttl, listener_bind_addr, route_group_count, RuntimeConfig,
};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use self::accept_loop::run_accept_loop;
use super::state::RuntimeState;

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    listener_platform::bind_tcp_listener(listener_bind_addr(config))
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    ensure_default_ttl(&mut config, listener_platform::detect_default_ttl)?;
    let state = RuntimeState::new(config, control.clone());
    let listener_addr = listener.local_addr()?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(listener_addr, client_capacity(&state.config), route_group_count(&state.config));
    }
    // Drain any autolearn events accumulated during policy load so that
    // telemetry reflects the initial state before any connections arrive.
    // The policy port's ServicesState::drop handles persistence on shutdown.
    {
        let _ = state.policy().drain_autolearn_events();
    }

    super::warmup::spawn_warmup_thread(state.clone());

    // Check for network identity changes and trigger a lightweight reprobe
    // if the network switched (e.g. WiFi -> cellular).
    super::reprobe::maybe_spawn_reprobe(&state);

    run_accept_loop(listener, state, control)
}
