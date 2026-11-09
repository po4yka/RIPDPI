use std::future::Future;
use std::io;
use std::sync::OnceLock;

use android_support::HandleRegistry;
use tokio::sync::watch;

static REQUESTS: OnceLock<HandleRegistry<watch::Sender<bool>>> = OnceLock::new();

fn requests() -> &'static HandleRegistry<watch::Sender<bool>> {
    REQUESTS.get_or_init(HandleRegistry::new)
}

pub(crate) fn create() -> u64 {
    let (sender, _) = watch::channel(false);
    requests().insert(sender)
}

pub(crate) fn cancel(handle: u64) {
    if let Some(sender) = requests().get(handle) {
        sender.send_replace(true);
    }
}

pub(crate) fn release(handle: u64) {
    if let Some(sender) = requests().remove(handle) {
        sender.send_replace(true);
    }
}

pub(crate) fn subscribe(handle: u64) -> io::Result<watch::Receiver<bool>> {
    requests()
        .get(handle)
        .map(|sender| sender.subscribe())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "ECH request handle is not active"))
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the operation and its owned DNS exchange or socket.
pub(crate) async fn run<T>(
    mut cancelled: watch::Receiver<bool>,
    operation: impl Future<Output = io::Result<T>>,
) -> io::Result<T> {
    tokio::select! {
        biased;
        _ = cancelled.wait_for(|cancelled| *cancelled) => {
            Err(io::Error::new(io::ErrorKind::Interrupted, "ECH handshake cancelled"))
        }
        result = operation => result,
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::io::AsyncReadExt;
    use tokio::net::TcpListener;
    use tokio::sync::oneshot;
    use tokio::time::timeout;

    use super::*;

    /// # Cancel safety:
    /// cancel-safe: all handles and the pending operation are released within this test.
    #[tokio::test]
    async fn precancelled_request_never_polls_its_operation() {
        let handle = create();
        cancel(handle);
        let error = run(subscribe(handle).expect("active handle"), async { Ok::<(), io::Error>(()) })
            .await
            .expect_err("cancelled");
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        release(handle);
        assert!(subscribe(handle).is_err());
        cancel(handle);
        release(handle);
    }

    /// # Cancel safety:
    /// NOT cancel-safe: runtime teardown stops a detached worker, but dropping the test
    /// can retain its request registry entry until process exit.
    #[tokio::test]
    async fn cancellation_closes_the_owned_socket_and_releases_the_request() {
        let handle = create();
        let receiver = subscribe(handle).expect("active handle");
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("loopback");
        let address = listener.local_addr().expect("address");
        let (started, running) = oneshot::channel();
        let worker = tokio::spawn(async move {
            let result = run(receiver, async {
                let mut socket = tokio::net::TcpStream::connect(address).await?;
                let _ = started.send(());
                let mut byte = [0u8; 1];
                socket.read_exact(&mut byte).await.map(|_| ())
            })
            .await;
            release(handle);
            result
        });
        let (mut peer, _) = listener.accept().await.expect("accept");
        running.await.expect("operation started");
        cancel(handle);
        let error = timeout(Duration::from_secs(1), worker)
            .await
            .expect("prompt cancellation")
            .expect("worker joined")
            .expect_err("cancelled");
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        assert_eq!(
            timeout(Duration::from_secs(1), peer.read(&mut [0u8; 1])).await.expect("peer EOF").expect("read"),
            0
        );
        assert!(subscribe(handle).is_err());
        let fresh = create();
        assert_ne!(fresh, handle);
        cancel(handle);
        assert!(!*subscribe(fresh).expect("fresh handle").borrow());
        release(fresh);
    }
}
