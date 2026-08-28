use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_native_protect::ProtectCallback;
use russh::client::{self, Handler};
use russh::keys::{HashAlg, PublicKey};
use tokio::net::TcpSocket;
use tokio::sync::Mutex;

/// An observed key, not an authorization to authenticate or open a channel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SshHostKeyObservation {
    pub fingerprint_sha256: String,
    pub algorithm: String,
}

/// Stable, credential-free outcomes of a key-only probe.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum SshHostKeyProbeError {
    #[error("invalid SSH probe input")]
    InvalidInput,
    #[error("SSH key observation timed out")]
    Timeout,
    #[error("SSH probe connection failed")]
    ConnectFailed,
    #[error("SSH key exchange failed")]
    HandshakeFailed,
    #[error("SSH probe socket protection denied")]
    ProtectionDenied,
    #[error("SSH key observation failed internally")]
    InternalFailure,
}

/// Observe a host key without transmitting a username or authentication material.
/// The supplied per-call controller must protect and bind the socket before I/O.
/// Call from a blocking thread, outside a Tokio runtime.
pub fn probe_host_key(
    address: SocketAddr,
    timeout: Duration,
    controller: Arc<dyn ProtectCallback>,
) -> Result<SshHostKeyObservation, SshHostKeyProbeError> {
    if address.port() == 0 || timeout.is_zero() || timeout > Duration::from_secs(30) {
        return Err(SshHostKeyProbeError::InvalidInput);
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| SshHostKeyProbeError::InternalFailure)?;
    let result = runtime.block_on(async {
        tokio::time::timeout(timeout, observe(address, controller.as_ref()))
            .await
            .map_err(|_| SshHostKeyProbeError::Timeout)?
    });
    // connect_stream starts its own KEX task before returning a Handle. A
    // timeout alone would detach that work on a shared runtime. This runtime
    // owns every probe task; dropping it cancels them and closes their sockets
    // before the caller can release the per-call Android service/controller.
    drop(runtime);
    drop(controller);
    result
}

struct ObserveOnly {
    observation: Arc<Mutex<Option<SshHostKeyObservation>>>,
}

impl Handler for ObserveOnly {
    type Error = russh::Error;

    /// # Cancel safety
    /// Cancel-safe: one owned observation is published before rejecting the key;
    /// no authentication or channel can be started by this handler.
    async fn check_server_key(&mut self, key: &PublicKey) -> Result<bool, Self::Error> {
        *self.observation.lock().await = Some(SshHostKeyObservation {
            fingerprint_sha256: key.fingerprint(HashAlg::Sha256).to_string(),
            algorithm: key.algorithm().to_string(),
        });
        Ok(false)
    }
}

/// # Cancel safety
/// Conditional: cancelling connect_stream may leave a russh KEX task. The
/// private caller destroys the dedicated runtime before returning on timeout.
async fn observe(
    address: SocketAddr,
    controller: &dyn ProtectCallback,
) -> Result<SshHostKeyObservation, SshHostKeyProbeError> {
    let socket = match address {
        SocketAddr::V4(_) => TcpSocket::new_v4(),
        SocketAddr::V6(_) => TcpSocket::new_v6(),
    }
    .map_err(|_| SshHostKeyProbeError::ConnectFailed)?;
    controller.protect(socket.as_raw_fd()).map_err(|_| SshHostKeyProbeError::ProtectionDenied)?;
    let stream = socket.connect(address).await.map_err(|_| SshHostKeyProbeError::ConnectFailed)?;
    let observation = Arc::new(Mutex::new(None));
    let handler = ObserveOnly { observation: Arc::clone(&observation) };
    match client::connect_stream(Arc::new(client::Config::default()), stream, handler).await {
        Err(russh::Error::UnknownKey) => observation.lock().await.take().ok_or(SshHostKeyProbeError::HandshakeFailed),
        // A successful connection is not expected: ObserveOnly always rejects.
        // Neither branch sends an authentication request.
        _ => Err(SshHostKeyProbeError::HandshakeFailed),
    }
}
