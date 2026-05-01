mod accept_loop;
mod client_job;
mod worker_pool;

use std::io;
use std::net::{SocketAddr, TcpListener};
use std::sync::Arc as StdArc;

use ripdpi_config::RuntimeConfig;
use ripdpi_runtime_api::EmbeddedProxyControl;
use ripdpi_runtime_platform as platform;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use self::accept_loop::run_accept_loop;
use super::state::{flush_autolearn_updates, RuntimeCleanup, RuntimeState};

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    let listen_addr = SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port);
    let domain = match listen_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_reuse_address(true)?;
    socket.bind(&SockAddr::from(listen_addr))?;
    socket.listen(1024)?;
    let listener: TcpListener = socket.into();
    listener.set_nonblocking(true)?;
    Ok(listener)
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    if config.network.default_ttl == 0 {
        config.network.default_ttl = platform::detect_default_ttl()?;
    }
    let state = RuntimeState::new(config, control.clone());
    let _cleanup = RuntimeCleanup::from_state(&state);
    let listener_addr = listener.local_addr()?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(listener_addr, state.config.network.max_open as usize, state.config.groups.len());
    }
    if let Ok(mut cache) = state.cache.write() {
        flush_autolearn_updates(&state, &mut cache);
    }

    super::warmup::spawn_warmup_thread(state.clone());

    // Check for network identity changes and trigger a lightweight reprobe
    // if the network switched (e.g. WiFi -> cellular).
    super::reprobe::maybe_spawn_reprobe(&state);

    run_accept_loop(listener, state, control)
}
