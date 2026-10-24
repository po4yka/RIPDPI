//! QUIC socket adapter with one logical peer and rotating physical UDP paths.

use std::fmt;
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::task::{Context, Poll, Waker};

use quinn::{AsyncUdpSocket, Runtime, UdpPoller};
use tokio::time::{Instant, Sleep};

use crate::hopping_policy::PortHoppingConfig;
use crate::salamander::SalamanderUdpSocket;
use crate::tls_quic::{ClientSocketSpec, build_client_udp_socket};

/// Lock order: timer and state are never held together. Protection and wake
/// callbacks run with neither lock held. Socket polling is nonblocking.
pub(crate) struct HoppingSocket {
    logical_peer: SocketAddr,
    policy: PortHoppingConfig,
    spec: ClientSocketSpec,
    receive_segments: usize,
    may_fragment: bool,
    state: Mutex<State>,
    timer: Mutex<Option<Pin<Box<Sleep>>>>,
}

/// Owns shutdown while a handshake is pending and after the client is built.
/// The guard has no task handle and no reference cycle.
pub(crate) struct StopHoppingOnDrop(Option<Arc<HoppingSocket>>);

impl StopHoppingOnDrop {
    pub(crate) fn new(socket: Option<Arc<HoppingSocket>>) -> Self {
        Self(socket)
    }
}

impl Drop for StopHoppingOnDrop {
    fn drop(&mut self) {
        if let Some(socket) = &self.0 {
            socket.stop();
        }
    }
}

struct PathSocket {
    io: Arc<dyn AsyncUdpSocket>,
    // This clone exposes the exact current socket to source-migration rollback.
    // It is dropped with its path, never retained for the whole hopping session.
    raw: std::net::UdpSocket,
}

struct State {
    current: Option<Arc<PathSocket>>,
    previous: Option<Arc<PathSocket>>,
    remote_index: usize,
    generation: u64,
    receiver: Option<Waker>,
    writers: Vec<Weak<Mutex<WriterState>>>,
}

impl fmt::Debug for HoppingSocket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HoppingSocket").field("policy", &self.policy).finish_non_exhaustive()
    }
}

impl HoppingSocket {
    pub(crate) fn new(
        logical_peer: SocketAddr,
        policy: PortHoppingConfig,
        spec: ClientSocketSpec,
        socket: std::net::UdpSocket,
    ) -> io::Result<Self> {
        let remote_index = policy.initial_index(&mut rand::rng());
        let deadline = next_deadline(&policy)?;
        let current = wrap_socket(socket, &spec)?;
        Ok(Self {
            logical_peer,
            policy,
            spec,
            receive_segments: current.io.max_receive_segments(),
            may_fragment: current.io.may_fragment(),
            state: Mutex::new(State {
                current: Some(current),
                previous: None,
                remote_index,
                generation: 0,
                receiver: None,
                writers: Vec::new(),
            }),
            timer: Mutex::new(Some(Box::pin(tokio::time::sleep_until(deadline)))),
        })
    }

    fn lock(&self) -> io::Result<MutexGuard<'_, State>> {
        // A panic during rotation must fail closed rather than expose a partial path.
        self.state.lock().map_err(|_| io::Error::other("Hysteria hopping state is unavailable"))
    }

    /// Rebind requested by the independent source-migration policy. The same
    /// adapter, destination selection and scheduled deadline stay in place.
    pub(crate) fn rebind(&self, socket: std::net::UdpSocket) -> io::Result<()> {
        self.replace_socket(wrap_socket(socket, &self.spec)?, false)
    }

    fn replace_socket(&self, socket: Arc<PathSocket>, change_remote: bool) -> io::Result<()> {
        // Quinn sizes receive buffers once from this capability. A later path
        // must never require a larger GRO buffer or weaken fragmentation policy.
        if socket.io.max_receive_segments() > self.receive_segments || socket.io.may_fragment() != self.may_fragment {
            return Err(io::Error::other("Hysteria replacement socket capabilities changed"));
        }
        let wakers = {
            let mut state = self.lock()?;
            if state.current.is_none() {
                return Err(stopped());
            }
            state.previous = state.current.replace(socket);
            if change_remote {
                state.remote_index = self.policy.next_index(state.remote_index, &mut rand::rng());
            }
            state.generation = state.generation.wrapping_add(1);
            take_wakers(&mut state)
        };
        for waker in wakers {
            waker.wake();
        }
        Ok(())
    }

    fn poll_hop(&self, cx: &mut Context<'_>) -> io::Result<()> {
        let due = {
            let Ok(mut timer) = self.timer.lock() else {
                return Err(io::Error::other("Hysteria hopping timer is unavailable"));
            };
            let Some(timer) = timer.as_mut() else {
                return Err(stopped());
            };
            if timer.as_mut().poll(cx).is_ready() {
                let deadline = next_deadline(&self.policy)?;
                timer.as_mut().reset(deadline);
                // Register the next wake even if this receive call returns data.
                let _ = timer.as_mut().poll(cx);
                true
            } else {
                false
            }
        };
        if due {
            // Socket construction invokes the platform protection callback. It
            // must run outside both transport locks, before the new socket is used.
            let result = build_client_udp_socket(&self.spec)
                .and_then(|socket| wrap_socket(socket, &self.spec))
                .and_then(|socket| self.replace_socket(socket, true));
            if let Err(error) = result {
                self.stop();
                return Err(error);
            }
        }
        Ok(())
    }

    pub(crate) fn current_socket(&self) -> io::Result<std::net::UdpSocket> {
        self.lock()?.current.as_ref().ok_or_else(stopped)?.raw.try_clone()
    }

    pub(crate) fn stop(&self) {
        // Poison still permits destruction: no policy is recovered for use.
        self.timer.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take();
        let wakers = {
            let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            state.current = None;
            state.previous = None;
            take_wakers(&mut state)
        };
        for waker in wakers {
            waker.wake();
        }
    }
}

fn stopped() -> io::Error {
    io::Error::new(io::ErrorKind::NotConnected, "Hysteria hopping socket is closed")
}

fn next_deadline(policy: &PortHoppingConfig) -> io::Result<Instant> {
    let interval = policy
        .window()
        .next_interval(&mut rand::rng())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Hysteria hopping interval is absent"))?;
    Instant::now()
        .checked_add(interval)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Hysteria hopping deadline is out of range"))
}

fn wrap_socket(socket: std::net::UdpSocket, spec: &ClientSocketSpec) -> io::Result<Arc<PathSocket>> {
    socket.set_nonblocking(true)?;
    let raw = socket.try_clone()?;
    let io: Arc<dyn AsyncUdpSocket> = if let Some(key) = &spec.salamander_key {
        Arc::new(SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?)
    } else {
        quinn::TokioRuntime.wrap_udp_socket(socket)?
    };
    Ok(Arc::new(PathSocket { io, raw }))
}

fn take_wakers(state: &mut State) -> Vec<Waker> {
    let mut wakers = state.receiver.take().into_iter().collect::<Vec<_>>();
    state.writers.retain(|writer| {
        let Some(writer) = writer.upgrade() else {
            return false;
        };
        let mut writer = writer.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        writer.inner = None;
        if let Some(waker) = writer.waker.take() {
            wakers.push(waker);
        }
        true
    });
    wakers
}

impl AsyncUdpSocket for HoppingSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        let writer = Arc::new(Mutex::new(WriterState::default()));
        // Registration cannot send data. A poisoned state is reported by the poller.
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).writers.push(Arc::downgrade(&writer));
        Box::pin(HoppingPoller { socket: self, writer })
    }

    fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> io::Result<()> {
        if transmit.destination != self.logical_peer {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "unexpected Hysteria logical peer"));
        }
        let (socket, port) = {
            let state = self.lock()?;
            (state.current.clone().ok_or_else(stopped)?, self.policy.ports()[state.remote_index])
        };
        let mut destination = self.logical_peer;
        destination.set_port(port);
        socket.io.try_send(&quinn::udp::Transmit { destination, ..transmit.clone() })
    }

    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [io::IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        if bufs.is_empty() || meta.is_empty() {
            return Poll::Ready(Err(io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria receive buffer")));
        }
        self.poll_hop(cx)?;
        let (current, previous) = {
            let mut state = self.lock()?;
            state.receiver = Some(cx.waker().clone());
            (state.current.clone().ok_or_else(stopped)?, state.previous.clone())
        };
        // Bound discarded traffic per poll so an unrelated sender cannot monopolize
        // the endpoint driver. Poll both paths even when one has no pending data.
        for socket in [previous, Some(current)].into_iter().flatten() {
            for attempt in 0..32 {
                match socket.io.poll_recv(cx, &mut bufs[..1], &mut meta[..1]) {
                    Poll::Ready(Ok(0)) | Poll::Pending => break,
                    Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                    Poll::Ready(Ok(_)) => {
                        if meta[0].addr.ip() == self.logical_peer.ip() && self.policy.contains_port(meta[0].addr.port())
                        {
                            meta[0].addr = self.logical_peer;
                            return Poll::Ready(Ok(1));
                        }
                        if attempt == 31 {
                            cx.waker().wake_by_ref();
                        }
                    }
                }
            }
        }
        Poll::Pending
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.lock()?.current.as_ref().ok_or_else(stopped)?.io.local_addr()
    }

    fn max_receive_segments(&self) -> usize {
        self.receive_segments
    }

    fn max_transmit_segments(&self) -> usize {
        self.lock()
            .ok()
            .and_then(|state| state.current.as_ref().map(|path| path.io.max_transmit_segments()))
            .unwrap_or(1)
    }

    fn may_fragment(&self) -> bool {
        self.may_fragment
    }
}

#[derive(Debug)]
struct HoppingPoller {
    socket: Arc<HoppingSocket>,
    writer: Arc<Mutex<WriterState>>,
}

#[derive(Debug, Default)]
struct WriterState {
    waker: Option<Waker>,
    inner: Option<(u64, Pin<Box<dyn UdpPoller>>)>,
}

impl UdpPoller for HoppingPoller {
    fn poll_writable(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let state = this.socket.lock()?;
        // Lock order: transport state -> writer slot. Rotation clears each old
        // poller under this same order so idle writers cannot retain retired fds.
        let mut writer = this.writer.lock().map_err(|_| io::Error::other("Hysteria writer state is unavailable"))?;
        writer.waker = Some(cx.waker().clone());
        let current = state.current.as_ref().ok_or_else(stopped)?;
        if writer.inner.as_ref().is_none_or(|(previous, _)| *previous != state.generation) {
            writer.inner = Some((state.generation, current.io.clone().create_io_poller()));
        }
        drop(state);
        match writer.inner.as_mut() {
            Some((_, poller)) => poller.as_mut().poll_writable(cx),
            None => Poll::Ready(Err(stopped())),
        }
    }
}

#[cfg(test)]
#[path = "hopping_socket_tests.rs"]
mod tests;
