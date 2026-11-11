use std::convert::Infallible;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex, Weak};
use std::task::Poll;
use std::time::Duration;

use tokio::sync::Semaphore;

use crate::{RelayCapabilities, RelayMux, RelayPoolConfig, RelaySession, RelaySessionFactory};

#[derive(Clone)]
struct IndependentDatagramFactory {
    stream_session: Arc<Mutex<Weak<Session>>>,
    release_datagram: Arc<Semaphore>,
}

struct Session {
    release_datagram: Arc<Semaphore>,
}

impl RelaySession for Session {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ();
    type Error = Infallible;

    /// # Cancel safety:
    /// cancel-safe: the returned stream owns its local pipe.
    async fn open_stream(&self, _target: &str) -> Result<Self::Stream, Self::Error> {
        Ok(tokio::io::duplex(8).0)
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation removes the pending datagram waiter.
    async fn open_datagram(&self) -> Result<(), Self::Error> {
        self.release_datagram.acquire().await.expect("datagram release stays open").forget();
        Ok(())
    }
}

impl RelaySessionFactory for IndependentDatagramFactory {
    type Session = Session;
    type Error = Infallible;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn datagrams_use_shared_session(&self) -> bool {
        false
    }

    /// # Cancel safety:
    /// cancel-safe: construction has no await or background work.
    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let session = Arc::new(Session { release_datagram: self.release_datagram.clone() });
        *self.stream_session.lock().expect("stream session observation") = Arc::downgrade(&session);
        Ok(session)
    }

    /// # Cancel safety:
    /// cancel-safe: construction has no await or background work.
    async fn create_datagram_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        Ok(Arc::new(Session { release_datagram: self.release_datagram.clone() }))
    }

    /// # Cancel safety:
    /// cancel-safe: the factory owns no running task.
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }
}

fn fixture(idle_timeout: Duration) -> (IndependentDatagramFactory, RelayMux<IndependentDatagramFactory>) {
    let factory = IndependentDatagramFactory {
        stream_session: Arc::new(Mutex::new(Weak::new())),
        release_datagram: Arc::new(Semaphore::new(0)),
    };
    let mux = RelayMux::new(factory.clone(), RelayPoolConfig { idle_timeout, ..RelayPoolConfig::default() });
    (factory, mux)
}

/// # Cancel safety:
/// cancel-safe: the caller retains the future after its first poll.
async fn poll_pending<F: Future>(mut future: Pin<&mut F>) {
    std::future::poll_fn(|cx| {
        assert!(future.as_mut().poll(cx).is_pending());
        Poll::Ready(())
    })
    .await;
}

/// # Cancel safety:
/// cancel-safe: both the stream and pending datagram remain local and are dropped.
#[tokio::test(flavor = "current_thread")]
async fn cancelling_independent_datagram_keeps_the_cached_stream_session() {
    let (factory, mux) = fixture(Duration::from_secs(30));
    let stream = mux.open_stream("target.example:443").await.expect("stream opens");
    let shared = factory.stream_session.lock().expect("observe stream session").clone();
    let mut datagram = Box::pin(mux.open_datagram());
    poll_pending(datagram.as_mut()).await;
    drop(datagram);
    drop(stream);
    assert!(shared.upgrade().is_some(), "datagram cancellation must preserve the cached stream owner");
    assert_eq!(1, mux.health().idle_streams);
    drop(mux.open_stream("target.example:443").await.expect("reuse stream session"));
    assert!(shared.upgrade().is_some(), "the original stream session remains reusable");
}

/// # Cancel safety:
/// cancel-safe: dropping the datagram future returns its permit.
#[tokio::test(flavor = "current_thread")]
async fn independent_datagram_does_not_construct_a_stream_session() {
    let (factory, mux) = fixture(Duration::from_secs(30));
    let mut datagram = Box::pin(mux.open_datagram());
    poll_pending(datagram.as_mut()).await;
    assert!(factory.stream_session.lock().expect("observe stream session").upgrade().is_none());
    drop(datagram);
    assert_eq!(0, mux.health().busy_streams);
}

/// # Cancel safety:
/// cancel-safe: all leases remain local and are dropped when the test exits.
#[tokio::test(flavor = "current_thread")]
async fn active_independent_datagram_does_not_extend_the_stream_idle_deadline() {
    let (factory, mux) = fixture(Duration::from_millis(30));
    drop(mux.open_stream("target.example:443").await.expect("stream opens"));
    let shared = factory.stream_session.lock().expect("observe stream session").clone();
    factory.release_datagram.add_permits(1);
    let datagram = mux.open_datagram().await.expect("independent datagram opens");
    tokio::time::sleep(Duration::from_millis(60)).await;
    let health = mux.health();
    assert_eq!(1, health.busy_streams);
    assert_eq!(0, health.idle_streams);
    assert!(shared.upgrade().is_none(), "the idle stream owner expires independently of a datagram lease");
    drop(datagram);
    assert_eq!(0, mux.health().busy_streams);
}
