use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::SshSessionFactory;

/// Build the SSH relay backend.
///
/// SSH is TCP-only (a `direct-tcpip` channel) and non-reusable (one fresh
/// session per flow). This wires the real `russh` engine in `ripdpi-ssh`:
/// `build` validates the config and returns a [`RelayBackend::Ssh`] whose
/// [`SshSessionFactory`] dials, verifies the host key (TOFU / strict pin),
/// authenticates by password or private key, and opens one `direct-tcpip`
/// channel per relay flow.
///
/// The relay policy is propagated to the SSH session factory. VPN mode accepts
/// only IP-literal carriers and protects the TCP fd before connect; proxy mode
/// retains hostname resolution without requiring a protection callback.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Ssh(ssh) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected SSH config"));
    };
    let port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SSH server port must fit u16"))?;
    let username = ssh
        .username
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing SSH username"))?;

    let auth = match ssh.auth_type.as_str() {
        "password" => {
            let password = ssh
                .password
                .clone()
                .filter(|value| !value.is_empty())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing SSH password"))?;
            ripdpi_relay_tls_transports::SshAuth::Password(password)
        }
        "private_key" => {
            let pem = ssh
                .private_key
                .clone()
                .filter(|value| !value.trim().is_empty())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing SSH private key"))?;
            let passphrase = ssh.private_key_passphrase.clone().filter(|value| !value.is_empty());
            ripdpi_relay_tls_transports::SshAuth::PrivateKey { pem, passphrase }
        }
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported SSH auth_type `{other}`; only password and private_key are supported"),
            ));
        }
    };

    let host_key_policy = if ssh.strict_host_key {
        let fingerprint =
            ssh.host_key_fingerprint.clone().filter(|value| !value.trim().is_empty()).ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "strict SSH host-key policy requires a fingerprint")
            })?;
        ripdpi_relay_tls_transports::SshHostKeyPolicy::Strict { fingerprint }
    } else {
        let pinned_fingerprint = ssh.host_key_fingerprint.clone().filter(|value| !value.trim().is_empty());
        ripdpi_relay_tls_transports::SshHostKeyPolicy::Tofu { pinned_fingerprint }
    };

    let client_config = ripdpi_relay_tls_transports::SshConfig {
        host: config.common.server.clone(),
        port,
        username,
        auth,
        host_key_policy,
    };
    client_config.validate().map_err(to_io_error)?;

    Ok(RelayBackend::Ssh(PooledRelayBackend::new(
        SshSessionFactory::new(client_config, context.socket_protection),
        context.pool_config,
        None,
    )))
}
