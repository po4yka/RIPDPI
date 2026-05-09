mod accept_loop;
mod client_job;
mod worker_pool;

use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc as StdArc;

use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;

use crate::process;

use self::accept_loop::run_accept_loop;
use super::config::RuntimeConfig;
use super::state::RuntimeState;

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    listener_platform::bind_tcp_listener(RuntimeState::listener_bind_addr(config))
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    RuntimeState::ensure_config_default_ttl(&mut config, listener_platform::detect_default_ttl)?;
    let state = RuntimeState::new(config, control.clone());
    let client_capacity = state.listener_client_capacity();
    let listener_addr = listener.local_addr()?;
    state.note_listener_started(listener_addr, client_capacity, state.listener_route_group_count());
    // Drain any autolearn events accumulated during policy load so that
    // telemetry reflects the initial state before any connections arrive.
    // The policy port's ServicesState::drop handles persistence on shutdown.
    state.drain_autolearn_events();

    super::warmup::spawn_warmup_thread(state.clone());

    // Check for network identity changes and trigger a lightweight reprobe
    // if the network switched (e.g. WiFi -> cellular).
    super::reprobe::maybe_spawn_reprobe(&state);

    run_accept_loop(listener, state, RuntimeShutdown::new(control), client_capacity)
}

pub(super) fn close_rejected_client(client: &TcpStream) {
    listener_platform::close_rejected_client(client);
}

#[derive(Clone)]
pub(super) struct RuntimeShutdown {
    control: Option<StdArc<EmbeddedProxyControl>>,
}

impl RuntimeShutdown {
    fn new(control: Option<StdArc<EmbeddedProxyControl>>) -> Self {
        Self { control }
    }

    pub(super) fn requested(&self) -> bool {
        self.control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested())
    }
}
