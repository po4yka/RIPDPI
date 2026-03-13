use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use crate::adaptive_tuning::AdaptivePlannerResolver;
use crate::retry_stealth::RetryPacer;
use crate::runtime_policy::RuntimeCache;
use crate::{current_runtime_telemetry, EmbeddedProxyControl};
use crate::{platform, process};
use ciadpi_config::RuntimeConfig;
use mio::net::TcpListener as MioTcpListener;
use mio::{Events, Interest, Poll};
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

use std::sync::atomic::AtomicUsize;
use std::sync::Mutex;

use super::state::{flush_autolearn_updates, ClientSlotGuard, RuntimeCleanup, RuntimeState, LISTENER};

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    let listen_addr = SocketAddr::new(config.listen.listen_ip, config.listen.listen_port);
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
    control: Option<Arc<EmbeddedProxyControl>>,
) -> io::Result<()> {
    let mut config = config;
    if config.default_ttl == 0 {
        config.default_ttl = platform::detect_default_ttl()?;
    }
    let cache = RuntimeCache::load(&config);
    let state = RuntimeState {
        config: Arc::new(config),
        cache: Arc::new(Mutex::new(cache)),
        adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
        adaptive_tuning: Arc::new(Mutex::new(AdaptivePlannerResolver::default())),
        retry_stealth: Arc::new(Mutex::new(RetryPacer::default())),
        active_clients: Arc::new(AtomicUsize::new(0)),
        telemetry: control.as_ref().and_then(|value| value.telemetry_sink()).or_else(current_runtime_telemetry),
        runtime_context: control.as_ref().and_then(|value| value.runtime_context()),
    };
    let _cleanup = RuntimeCleanup { config: state.config.clone(), cache: state.cache.clone() };
    listener.set_nonblocking(true)?;
    let mut listener = MioTcpListener::from_std(listener);
    let mut poll = Poll::new()?;
    poll.registry().register(&mut listener, LISTENER, Interest::READABLE)?;
    let mut events = Events::with_capacity(256);
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_started(
            SocketAddr::new(state.config.listen.listen_ip, state.config.listen.listen_port),
            state.config.max_open as usize,
            state.config.groups.len(),
        );
    }
    if let Ok(mut cache) = state.cache.lock() {
        flush_autolearn_updates(&state, &mut cache);
    }

    let result = loop {
        let shutdown_requested =
            control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested());
        if shutdown_requested {
            break Ok(());
        }
        poll.poll(&mut events, Some(Duration::from_millis(250)))?;
        for event in &events {
            if event.token() != LISTENER {
                continue;
            }
            loop {
                match listener.accept() {
                    Ok((stream, _addr)) => {
                        let state = state.clone();
                        let client = mio_to_std_stream(stream);
                        client.set_nonblocking(false)?;
                        let Some(_slot) =
                            ClientSlotGuard::acquire(state.active_clients.clone(), state.config.max_open as usize)
                        else {
                            let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
                            drop(client);
                            continue;
                        };
                        if let Some(telemetry) = &state.telemetry {
                            telemetry.on_client_accepted();
                        }
                        thread::Builder::new()
                            .name("ripdpi-client".into())
                            .spawn(move || {
                                let _slot = _slot;
                                let result = super::handle_client(client, &state);
                                if let Err(err) = &result {
                                    log::error!("ciadpi client error: {err}");
                                    if let Some(telemetry) = &state.telemetry {
                                        telemetry.on_client_error(err);
                                    }
                                }
                                if let Some(telemetry) = &state.telemetry {
                                    telemetry.on_client_finished();
                                }
                            })
                            .expect("failed to spawn client thread");
                    }
                    Err(err) if err.kind() == io::ErrorKind::WouldBlock => break,
                    Err(err) => return Err(err),
                }
            }
        }
    };
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_listener_stopped();
    }
    result
}

fn mio_to_std_stream(stream: mio::net::TcpStream) -> TcpStream {
    use std::os::fd::{FromRawFd, IntoRawFd};

    let fd = stream.into_raw_fd();
    // SAFETY: ownership of the file descriptor is moved out of the mio stream
    // and transferred directly into the std stream without duplication.
    unsafe { TcpStream::from_raw_fd(fd) }
}
