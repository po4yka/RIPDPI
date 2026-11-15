use std::future::Future;
use std::io;
use std::sync::OnceLock;

use android_support::HandleRegistry;
use tokio::sync::watch;

use crate::HttpRequestId;

const MAX_ACTIVE_REQUESTS: usize = 64;
static REQUESTS: OnceLock<HandleRegistry<watch::Sender<bool>>> = OnceLock::new();

fn requests() -> &'static HandleRegistry<watch::Sender<bool>> {
    REQUESTS.get_or_init(HandleRegistry::new)
}

pub(crate) fn create() -> HttpRequestId {
    let (sender, _) = watch::channel(false);
    HttpRequestId(requests().try_insert_bounded(sender, MAX_ACTIVE_REQUESTS).unwrap_or(0))
}

pub(crate) fn cancel(handle: HttpRequestId) {
    if let Some(sender) = requests().get(handle.0) {
        sender.send_replace(true);
    }
}

pub(crate) fn release(handle: HttpRequestId) {
    if let Some(sender) = requests().remove(handle.0) {
        sender.send_replace(true);
    }
}

pub(crate) fn subscribe(handle: HttpRequestId) -> io::Result<watch::Receiver<bool>> {
    requests()
        .get(handle.0)
        .map(|sender| sender.subscribe())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "HTTP request handle is not active"))
}

/// # Cancel safety:
/// cancel-safe: if dropping `operation` cancels its owned work. Cancellation drops
/// the operation; the HTTP executor also drops its per-call runtime to stop detached
/// connection tasks. Remote request effects are not rolled back.
pub(crate) async fn run<T>(
    mut cancelled: watch::Receiver<bool>,
    operation: impl Future<Output = io::Result<T>>,
) -> io::Result<T> {
    tokio::select! {
        biased;
        _ = cancelled.wait_for(|cancelled| *cancelled) => {
            Err(io::Error::new(io::ErrorKind::Interrupted, "native TLS fetch cancelled"))
        }
        result = operation => result,
    }
}

#[cfg(test)]
#[path = "http_cancellation_tests.rs"]
mod tests;
