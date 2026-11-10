use russh::keys::{PrivateKey, decode_secret_key};
use tokio::sync::Semaphore;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::{Result, SshError};

// All connections share one slot, including during non-preemptible KDF work.
static DECODE_SLOT: Semaphore = Semaphore::const_new(1);

/// # Cancel safety:
/// NOT cancel-safe: the owned constructor must poll this future through join.
/// Cancelling a waiter before admission starts no job; an admitted blocking
/// job keeps its permit and credentials until the constructor observes its exit.
pub(super) async fn decode(
    pem: String,
    passphrase: Option<String>,
    cancel: &CancellationToken,
    deadline: Instant,
) -> Result<PrivateKey> {
    let permit = tokio::select! {
        biased;
        _ = cancel.cancelled() => return Err(cancelled()),
        _ = tokio::time::sleep_until(deadline) => return Err(timed_out()),
        permit = DECODE_SLOT.acquire() => permit.map_err(|_| SshError::CleanupFailed)?,
    };
    let worker_cancel = cancel.clone();
    let decoded = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        if worker_cancel.is_cancelled() {
            return Err(cancelled());
        }
        if Instant::now() >= deadline {
            return Err(timed_out());
        }
        decode_secret_key(&pem, passphrase.as_deref())
            .map_err(|error| SshError::Ssh(format!("private key decode failed: {error}")))
    })
    .await
    .map_err(|_| SshError::CleanupFailed)?;
    if cancel.is_cancelled() {
        return Err(cancelled());
    }
    if Instant::now() >= deadline {
        return Err(timed_out());
    }
    decoded
}

fn cancelled() -> SshError {
    SshError::Ssh("SSH connection cancelled".into())
}

fn timed_out() -> SshError {
    SshError::Io(std::io::Error::new(std::io::ErrorKind::TimedOut, "SSH connection timed out"))
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    /// # Cancel safety:
    /// NOT cancel-safe: the test joins the real decode and retains the slot
    /// while it exercises cancellation and timeout before admission.
    #[tokio::test]
    async fn queued_cancel_and_deadline_do_not_start_work_and_admitted_work_is_joined() {
        let permit = DECODE_SLOT.acquire().await.expect("hold global slot");
        let cancel = CancellationToken::new();
        let signal = cancel.clone();
        let cancellation = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(10)).await;
            signal.cancel();
        });
        let pem = include_str!("../../tests/fixtures/kdf64.pem");
        let queued = tokio::time::timeout(
            Duration::from_millis(250),
            decode(pem.into(), Some("h1-local-passphrase".into()), &cancel, Instant::now() + Duration::from_secs(5)),
        )
        .await
        .expect("cancelled queue must not wait for the occupied slot");
        cancellation.await.expect("cancel task");
        assert!(queued.expect_err("queued cancellation").to_string().contains("cancelled"));
        let expired = decode(pem.into(), None, &CancellationToken::new(), Instant::now()).await;
        assert!(matches!(expired, Err(SshError::Io(error)) if error.kind() == std::io::ErrorKind::TimedOut));
        drop(permit);

        let cancel = CancellationToken::new();
        let signal = cancel.clone();
        let cancellation = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(100)).await;
            signal.cancel();
        });
        let admitted =
            decode(pem.into(), Some("h1-local-passphrase".into()), &cancel, Instant::now() + Duration::from_secs(5))
                .await;
        cancellation.await.expect("admitted cancel task");
        assert!(admitted.expect_err("admitted cancellation").to_string().contains("cancelled"));
        let _released = DECODE_SLOT.try_acquire().expect("completed decode leaves no live worker holding the slot");
    }
}
