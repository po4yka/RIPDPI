use std::future::Future;
use std::io::{self, Read};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::task::Poll;
use std::time::Duration;

use tokio::sync::Semaphore;

use crate::{RelayCapabilities, RelayMux, RelayPoolConfig, RelaySession, RelaySessionFactory};

#[derive(Clone)]
struct LateDialFactory {
    endpoint: SocketAddr,
    release: Arc<Semaphore>,
    reusable: bool,
}

struct LateDialSession {
    endpoint: SocketAddr,
    release: Arc<Semaphore>,
}

impl LateDialSession {
    /// # Cancel safety:
    /// cancel-safe: a pending handshake owns and drops its loopback socket.
    async fn connect(&self) -> io::Result<TcpStream> {
        let socket = TcpStream::connect_timeout(&self.endpoint, Duration::from_secs(1))?;
        self.release.acquire().await.expect("handshake release stays open").forget();
        Ok(socket)
    }
}

impl RelaySession for LateDialSession {
    type Stream = TcpStream;
    type Datagram = TcpStream;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: the incomplete transport belongs to this future.
    async fn open_stream(&self, _target: &str) -> io::Result<Self::Stream> {
        self.connect().await
    }

    /// # Cancel safety:
    /// cancel-safe: the incomplete transport belongs to this future.
    async fn open_datagram(&self) -> io::Result<Self::Datagram> {
        self.connect().await
    }
}

impl RelaySessionFactory for LateDialFactory {
    type Session = LateDialSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: self.reusable }
    }

    /// # Cancel safety:
    /// cancel-safe: construction has no suspension or worker task.
    async fn create_session(&self) -> io::Result<Arc<Self::Session>> {
        Ok(Arc::new(LateDialSession { endpoint: self.endpoint, release: Arc::clone(&self.release) }))
    }

    /// # Cancel safety:
    /// cancel-safe: the factory owns no background work.
    async fn shutdown(&self) -> io::Result<()> {
        Ok(())
    }
}

fn fixture(reusable: bool) -> (TcpListener, RelayMux<LateDialFactory>, Arc<Semaphore>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback peer");
    listener.set_nonblocking(true).expect("nonblocking peer");
    let release = Arc::new(Semaphore::new(0));
    let factory =
        LateDialFactory { endpoint: listener.local_addr().expect("peer address"), release: release.clone(), reusable };
    let mux = RelayMux::new(factory, RelayPoolConfig { max_active_leases: 64, ..RelayPoolConfig::default() });
    (listener, mux, release)
}

fn accept_ready(listener: &TcpListener) -> Vec<TcpStream> {
    let mut peers = Vec::new();
    loop {
        match listener.accept() {
            Ok((peer, _)) => peers.push(peer),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => return peers,
            Err(error) => panic!("accept loopback connection: {error}"),
        }
    }
}

/// # Cancel safety:
/// cancel-safe: the caller retains the pending future after one poll.
async fn poll_pending<F: Future>(mut future: Pin<&mut F>) {
    std::future::poll_fn(|cx| {
        assert!(future.as_mut().poll(cx).is_pending());
        Poll::Ready(())
    })
    .await;
}

/// # Cancel safety:
/// cancel-safe: the caller retains pending opens and accepted peers stay local until return.
async fn next_peers_after_polling<F: Future>(listener: &TcpListener, opens: &mut [Pin<Box<F>>]) -> Vec<TcpStream> {
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            for open in opens.iter_mut() {
                poll_pending(open.as_mut()).await;
            }
            let peers = accept_ready(listener);
            if !peers.is_empty() {
                return peers;
            }
            // Manual polling may stop at Tokio's cooperative budget before connect.
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("an admitted open reaches the peer")
}

/// # Cancel safety:
/// cancel-safe: all pending opens and peer sockets remain local to this future.
async fn late_stream_opens_are_bounded(reusable: bool) {
    let (listener, mux, release) = fixture(reusable);
    let mut opens: Vec<_> = (0..40).map(|_| Box::pin(mux.open_stream("unused.example:443"))).collect();
    for open in &mut opens {
        poll_pending(open.as_mut()).await;
    }
    let mut peers = accept_ready(&listener);
    assert_eq!(8, peers.len(), "only eight incomplete opens may reach the peer");

    release.add_permits(1);
    let established = opens.remove(0).await.expect("finish one handshake");
    let next = next_peers_after_polling(&listener, &mut opens).await;
    assert_eq!(1, next.len(), "an established stream must release its opening slot");
    peers.extend(next);
    drop(established);
    drop(opens);
    assert_eq!(0, mux.health().busy_streams, "cancellation returns lease accounting");
    for mut peer in peers {
        peer.set_nonblocking(false).expect("blocking peer EOF read");
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer read deadline");
        assert_eq!(0, peer.read(&mut [0; 1]).expect("cancelled connection closes"));
    }
    release.add_permits(1);
    drop(
        tokio::time::timeout(Duration::from_secs(1), mux.open_stream("unused.example:443"))
            .await
            .expect("opening slot returned")
            .expect("cancellation returns opening slots"),
    );
}

/// # Cancel safety:
/// cancel-safe: the helper owns and drops every pending connection.
#[tokio::test(flavor = "current_thread")]
async fn non_reusable_late_dials_are_bounded_through_initial_open() {
    late_stream_opens_are_bounded(false).await;
}

/// # Cancel safety:
/// cancel-safe: the helper owns and drops every pending connection.
#[tokio::test(flavor = "current_thread")]
async fn reusable_context_late_dials_are_bounded_through_initial_open() {
    late_stream_opens_are_bounded(true).await;
}

/// # Cancel safety:
/// cancel-safe: dropping pending stream and datagram opens closes their sockets.
#[tokio::test(flavor = "current_thread")]
async fn streams_and_datagrams_share_the_establishment_budget() {
    let (listener, mux, release) = fixture(true);
    let mut streams: Vec<_> = (0..8).map(|_| Box::pin(mux.open_stream("unused.example:443"))).collect();
    let mut datagrams: Vec<_> = (0..8).map(|_| Box::pin(mux.open_datagram())).collect();
    for stream in &mut streams {
        poll_pending(stream.as_mut()).await;
    }
    for datagram in &mut datagrams {
        poll_pending(datagram.as_mut()).await;
    }
    assert_eq!(8, accept_ready(&listener).len(), "TCP and datagram opens share eight slots");
    drop(streams.remove(0));
    let next = next_peers_after_polling(&listener, std::slice::from_mut(&mut datagrams[0])).await;
    assert_eq!(1, next.len(), "cancelled TCP open admits a datagram open");
    release.add_permits(8);
    let established = tokio::time::timeout(Duration::from_secs(1), datagrams.remove(0))
        .await
        .expect("datagram handshake released")
        .expect("finish datagram handshake");
    let next = next_peers_after_polling(&listener, std::slice::from_mut(&mut datagrams[0])).await;
    assert_eq!(1, next.len(), "an established datagram releases its opening slot");
    drop(established);
    drop(streams);
    drop(datagrams);
    assert_eq!(0, mux.health().busy_streams);
}

#[derive(Clone)]
struct RetryingFactory {
    next_generation: Arc<AtomicUsize>,
    release_failures: Arc<Semaphore>,
}

struct RetryingSession {
    generation: usize,
    release_failures: Arc<Semaphore>,
}

impl RelaySession for RetryingSession {
    type Stream = ();
    type Datagram = usize;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: warming the cache has no asynchronous state.
    async fn open_stream(&self, _target: &str) -> io::Result<()> {
        Ok(())
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation only removes the pending failure waiter.
    async fn open_datagram(&self) -> io::Result<usize> {
        if self.generation == 0 {
            self.release_failures.acquire().await.expect("failure gate stays open").forget();
            return Err(io::Error::new(io::ErrorKind::ConnectionReset, "cached carrier failed"));
        }
        Ok(self.generation)
    }
}

impl RelaySessionFactory for RetryingFactory {
    type Session = RetryingSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    /// # Cancel safety:
    /// cancel-safe: construction has no suspension or owned task.
    async fn create_session(&self) -> io::Result<Arc<Self::Session>> {
        Ok(Arc::new(RetryingSession {
            generation: self.next_generation.fetch_add(1, Ordering::Relaxed),
            release_failures: Arc::clone(&self.release_failures),
        }))
    }

    /// # Cancel safety:
    /// cancel-safe: the factory owns no background work.
    async fn shutdown(&self) -> io::Result<()> {
        Ok(())
    }
}

/// # Cancel safety:
/// cancel-safe: JoinSet aborts every unfinished request if the test exits early.
#[tokio::test(flavor = "current_thread")]
async fn stale_retries_release_opening_slots_before_waiting_for_lease_capacity() {
    let release_failures = Arc::new(Semaphore::new(0));
    let factory =
        RetryingFactory { next_generation: Arc::new(AtomicUsize::new(0)), release_failures: release_failures.clone() };
    let mux = RelayMux::new(factory, RelayPoolConfig { max_active_leases: 8, ..RelayPoolConfig::default() });
    drop(mux.open_stream("warm.example:443").await.expect("warm the cached carrier"));
    let mut requests = tokio::task::JoinSet::new();
    for _ in 0..8 {
        let mux = mux.clone();
        requests.spawn(async move { mux.open_datagram().await.map(|lease| *lease.get_ref()) });
    }
    tokio::time::timeout(Duration::from_secs(1), async {
        while mux.health().busy_streams != 8 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("all initial requests reach the failing carrier");
    for _ in 0..8 {
        let mux = mux.clone();
        requests.spawn(async move { mux.open_datagram().await.map(|lease| *lease.get_ref()) });
    }
    tokio::time::timeout(Duration::from_secs(1), async {
        while mux.health().backpressure_events < 8 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("competing requests wait for lease capacity");
    release_failures.add_permits(8);
    let completed = tokio::time::timeout(Duration::from_secs(2), async {
        let mut completed = 0;
        while let Some(result) = requests.join_next().await {
            assert_eq!(1, result.expect("request task").expect("replacement carrier opens"));
            completed += 1;
        }
        completed
    })
    .await
    .expect("lease waiters and stale retries must not deadlock");
    assert_eq!(16, completed);
    assert_eq!(0, mux.health().busy_streams);
}
