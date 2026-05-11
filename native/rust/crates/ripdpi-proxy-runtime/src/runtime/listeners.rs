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
    let _root_helper = RootHelperRegistration::for_config(&config);
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

struct RootHelperRegistration {
    registered: bool,
}

impl RootHelperRegistration {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn for_config(config: &RuntimeConfig) -> Self {
        let Some(path) =
            config.process.root_helper_socket_path.as_deref().map(str::trim).filter(|path| !path.is_empty())
        else {
            return Self { registered: false };
        };
        ripdpi_proxy_runtime_adapter::platform::root_helper::register_root_helper(path.to_owned());
        Self { registered: true }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn for_config(_config: &RuntimeConfig) -> Self {
        Self { registered: false }
    }
}

impl Drop for RootHelperRegistration {
    fn drop(&mut self) {
        if self.registered {
            unregister_root_helper();
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn unregister_root_helper() {
    ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unregister_root_helper() {}

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

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use std::sync::Mutex;

    use super::*;

    static ROOT_HELPER_TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn root_helper_registration_uses_nonblank_proxy_socket_path_until_drop() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some(" /tmp/ripdpi-proxy-root-helper.sock ".to_string());

        let guard = RootHelperRegistration::for_config(&config);

        assert!(guard.registered);
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
        assert_eq!(ripdpi_proxy_runtime_adapter::platform::root_helper::with_root_helper(|_| ()), Some(()));

        drop(guard);
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }

    #[test]
    fn blank_proxy_socket_path_does_not_register_root_helper() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some(" \n\t ".to_string());

        let guard = RootHelperRegistration::for_config(&config);

        assert!(!guard.registered);
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }
}
