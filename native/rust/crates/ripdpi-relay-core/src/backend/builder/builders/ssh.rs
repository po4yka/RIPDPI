use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::SshSessionFactory;

/// Build the SSH relay backend.
///
/// SSH is TCP-only (a `direct-tcpip` channel) and non-reusable in this
/// foundation. The `russh` wire engine in `ripdpi-ssh` is stubbed because the
/// relay layer exposes no protected outbound connector to hand `russh` a
/// pre-connected `VpnService.protect()`-honoured stream (see the `ripdpi-ssh`
/// crate doc). The built backend therefore validates config and fails session
/// creation with `Unimplemented` rather than opening an unprotected socket.
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
        SshSessionFactory { config: client_config },
        context.pool_config,
        None,
    )))
}
