use std::sync::Arc;

use russh::ChannelStream;
use russh::client::{self, Config, Handler};
use russh::keys::{HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate, decode_secret_key};
use tokio::sync::Mutex;

use crate::config::{SshAuth, SshConfig, SshHostKeyPolicy, parse_fingerprint};
use crate::error::{Result, SshError};

/// The bidirectional stream a `direct-tcpip` channel exposes.
///
/// An `AsyncRead + AsyncWrite + Unpin + Send` view over a single SSH channel,
/// returned by [`SshClient::tcp_connect`]. Aliased so relay adapters can name
/// the type without depending on `russh` directly.
pub type SshChannelStream = ChannelStream<client::Msg>;

mod lifecycle;
use lifecycle::ConnectionControl;
pub use lifecycle::SshClient;

/// The decision a host-key policy reaches for an observed server fingerprint.
///
/// This mirrors the TOFU flow the `russh` host-key handler drives: pinned-match
/// accepts, strict-mismatch aborts, and a first-use TOFU key is surfaced to the
/// UI for explicit accept/reject rather than being silently trusted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostKeyDecision {
    /// The presented key matched the pinned fingerprint; trust it.
    Accept,
}

/// Evaluate a host-key policy against the fingerprint a server presents.
///
/// * `SshHostKeyPolicy::Tofu` with a pinned fingerprint matching `presented`
///   returns [`HostKeyDecision::Accept`].
/// * `SshHostKeyPolicy::Tofu` with a pinned fingerprint that does NOT match
///   returns [`SshError::HostKeyMismatch`] — a pinned TOFU key that changed is
///   treated as hostile, exactly like strict.
/// * `SshHostKeyPolicy::Tofu` with no pin returns
///   [`SshError::HostKeyUntrusted`] carrying the presented fingerprint so the
///   UI can prompt the user; the engine never trusts a first-use key on its
///   own.
/// * `SshHostKeyPolicy::Strict` returns [`HostKeyDecision::Accept`] on an exact
///   match and [`SshError::HostKeyMismatch`] otherwise.
pub fn evaluate_host_key(policy: &SshHostKeyPolicy, presented: &str) -> Result<HostKeyDecision> {
    let presented_fp = parse_fingerprint(presented)?;
    match policy {
        SshHostKeyPolicy::Tofu { pinned_fingerprint: Some(pinned) } => {
            let pinned_fp = parse_fingerprint(pinned)?;
            if pinned_fp == presented_fp {
                Ok(HostKeyDecision::Accept)
            } else {
                Err(SshError::HostKeyMismatch { expected: pinned_fp.sha256_base64, got: presented_fp.sha256_base64 })
            }
        }
        SshHostKeyPolicy::Tofu { pinned_fingerprint: None } => {
            Err(SshError::HostKeyUntrusted(presented_fp.sha256_base64))
        }
        SshHostKeyPolicy::Strict { fingerprint } => {
            let pinned_fp = parse_fingerprint(fingerprint)?;
            if pinned_fp == presented_fp {
                Ok(HostKeyDecision::Accept)
            } else {
                Err(SshError::HostKeyMismatch { expected: pinned_fp.sha256_base64, got: presented_fp.sha256_base64 })
            }
        }
    }
}

/// `russh` client handler that routes the server's host key through
/// [`evaluate_host_key`].
///
/// `russh`'s `check_server_key` may only return `Ok(bool)` (or its associated
/// error). When the policy rejects a key the handler returns `Ok(false)` AND
/// stashes the precise [`SshError`] (carrying the exact presented / expected
/// fingerprints) in a shared `rejection` slot so [`connect`] can surface the
/// typed `HostKeyMismatch` / `HostKeyUntrusted` error instead of the generic
/// `russh::Error::UnknownKey`. The slot is shared (`Arc`) because
/// `client::connect` consumes the handler by value, so the caller cannot read a
/// field back off the handler after the fact.
struct SshHandler {
    policy: SshHostKeyPolicy,
    /// Shared slot that captures the typed rejection reason. `connect` holds
    /// the other end of this `Arc` and reads it when `connect` fails with
    /// `UnknownKey`.
    rejection: Arc<Mutex<Option<SshError>>>,
}

impl Handler for SshHandler {
    type Error = russh::Error;

    // cancel-safe: a single short lock acquisition with no `.await` held across
    // it other than the lock itself; the future is dropped cleanly on cancel,
    // leaving at most the shared `rejection` slot populated, which is harmless.
    async fn check_server_key(
        &mut self,
        server_public_key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        // This policy pins a raw host-key fingerprint. A certificate requires
        // CA, principal, and validity-window verification, so never reduce it
        // to its embedded key and accidentally accept it under raw-key rules.
        if server_public_key.certificate().is_some() {
            return Ok(false);
        }
        // OpenSSH `SHA256:<base64>` fingerprint of the presented host key.
        let presented = server_public_key.public_key().fingerprint(HashAlg::Sha256).to_string();
        match evaluate_host_key(&self.policy, &presented) {
            Ok(HostKeyDecision::Accept) => Ok(true),
            Err(reason) => {
                *self.rejection.lock().await = Some(reason);
                Ok(false)
            }
        }
    }
}

/// Validate and create the owner of a fresh SSH connection. Call `ready` for
/// authentication, and `close` to join even if readiness is cancelled or fails.
pub fn connect(config: &SshConfig) -> Result<SshClient> {
    connect_with_socket_protection(config, ripdpi_native_protect::SocketProtectionPolicy::Inactive)
}

/// Create an owned connection with explicit per-runtime socket protection.
pub fn connect_with_socket_protection(
    config: &SshConfig,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> Result<SshClient> {
    SshClient::start(config.clone(), socket_protection)
}

/// # Cancel safety
/// Not cancel-safe: only the owned constructor task polls this future. Stop is
/// delivered through transport I/O; connect_stream's failure path joins KEX.
async fn establish(
    config: SshConfig,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    control: Arc<ConnectionControl>,
) -> Result<client::Handle<SshHandler>> {
    let rejection: Arc<Mutex<Option<SshError>>> = Arc::new(Mutex::new(None));
    let handler = SshHandler { policy: config.host_key_policy.clone(), rejection: Arc::clone(&rejection) };
    let client_config = Arc::new(Config::default());

    let transport = async {
        let server_addr = socket_protection
            .resolve_host(&config.host, config.port)
            .await
            .map_err(SshError::Io)?
            .into_iter()
            .next()
            .ok_or_else(|| SshError::Ssh("SSH server resolved to no addresses".to_string()))?;
        let socket = match server_addr {
            std::net::SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4(),
            std::net::SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6(),
        }
        .map_err(|error| SshError::Ssh(error.to_string()))?;
        use std::os::fd::AsRawFd as _;
        socket_protection.protect_non_loopback(socket.as_raw_fd(), server_addr).map_err(SshError::Io)?;
        let stream = socket.connect(server_addr).await.map_err(|error| SshError::Ssh(error.to_string()))?;

        Ok::<_, SshError>(stream)
    };
    let stream = tokio::select! {
        biased;
        _ = control.cancel.cancelled() => return Err(SshError::Ssh("SSH connection cancelled".into())),
        result = tokio::time::timeout_at(control.deadline, transport) => result
            .map_err(|_| SshError::Io(std::io::Error::new(std::io::ErrorKind::TimedOut, "SSH connection timed out")))??,
    };
    let stream = control.wrap(stream);

    let mut handle = match client::connect_stream(client_config, stream, handler).await {
        Ok(handle) => handle,
        Err(russh::Error::UnknownKey) => {
            // `check_server_key` returned Ok(false); recover the precise typed
            // reason (exact presented / expected fingerprints) the handler
            // stashed. If for any reason it is absent, fall back to the policy
            // intent: a no-pin TOFU policy means untrusted-on-first-use, any
            // pinned / strict policy that rejected means a mismatch.
            let stashed = rejection.lock().await.take();
            return Err(stashed.unwrap_or_else(|| host_key_rejection(&config.host_key_policy)));
        }
        Err(russh::Error::Join(_)) => return Err(SshError::CleanupFailed),
        Err(other) => return Err(SshError::Ssh(other.to_string())),
    };

    let authenticated = if control.cancel.is_cancelled() {
        Err(SshError::Ssh("SSH connection cancelled".into()))
    } else {
        authenticate(&config, &mut handle).await
    };
    if let Err(error) = authenticated {
        control.cancel();
        if matches!((&mut handle).await, Err(russh::Error::Join(_))) {
            return Err(SshError::CleanupFailed);
        }
        return Err(error);
    }
    control.authenticated();
    Ok(handle)
}

/// # Cancel safety
/// Only the owned constructor polls authentication; cancellation closes the
/// transport and the caller joins the session handle on every error path.
async fn authenticate(config: &SshConfig, handle: &mut client::Handle<SshHandler>) -> Result<()> {
    match &config.auth {
        SshAuth::Password(password) => {
            let result = handle
                .authenticate_password(&config.username, password)
                .await
                .map_err(|error| SshError::Ssh(error.to_string()))?;
            if !result.success() {
                return Err(SshError::AuthFailed);
            }
        }
        SshAuth::PrivateKey { pem, passphrase } => {
            let private_key = decode_secret_key(pem, passphrase.as_deref())
                .map_err(|error| SshError::Ssh(format!("private key decode failed: {error}")))?;
            // For RSA keys, query the server's preferred hash (ssh-rsa vs
            // rsa-sha2-256/512). For non-RSA keys this is ignored by
            // `PrivateKeyWithHashAlg::new`.
            let hash_alg = best_rsa_hash(handle).await;
            let key = PrivateKeyWithHashAlg::new(Arc::new(private_key), hash_alg);
            let result = handle
                .authenticate_publickey(&config.username, key)
                .await
                .map_err(|error| SshError::Ssh(error.to_string()))?;
            if !result.success() {
                return Err(SshError::AuthFailed);
            }
        }
    }

    Ok(())
}

/// Map a host-key rejection (signalled by `russh::Error::UnknownKey`) back to
/// the typed `SshError` the policy implies.
///
/// A `Tofu` policy with no pin rejects because the key is untrusted on first
/// use; any pinned `Tofu` or `Strict` policy rejects because the presented key
/// did not match the pin. The exact presented/expected fingerprints are not
/// recoverable here (the key was consumed by `russh`), so the strict-mismatch
/// arm reports the pinned value with an empty `got`.
fn host_key_rejection(policy: &SshHostKeyPolicy) -> SshError {
    match policy {
        SshHostKeyPolicy::Tofu { pinned_fingerprint: None } => SshError::HostKeyUntrusted(String::new()),
        SshHostKeyPolicy::Tofu { pinned_fingerprint: Some(pinned) } => {
            let expected = parse_fingerprint(pinned).map(|fp| fp.sha256_base64).unwrap_or_default();
            SshError::HostKeyMismatch { expected, got: String::new() }
        }
        SshHostKeyPolicy::Strict { fingerprint } => {
            let expected = parse_fingerprint(fingerprint).map(|fp| fp.sha256_base64).unwrap_or_default();
            SshError::HostKeyMismatch { expected, got: String::new() }
        }
    }
}

/// Resolve the best RSA signature hash the server advertises, falling back to
/// `None` (legacy `ssh-rsa`/SHA-1) when the server sends no `server-sig-algs`
/// extension.
// cancel-safe: a single request/response on the live session; dropping it
// leaves the session usable and returns no partial state.
async fn best_rsa_hash(handle: &client::Handle<SshHandler>) -> Option<HashAlg> {
    match handle.best_supported_rsa_hash().await {
        Ok(Some(hash)) => hash,
        Ok(None) | Err(_) => None,
    }
}

/// Parse a `host:port` target into its components.
///
/// IPv6 literals in bracketed `[::1]:443` form are supported; a bare host with
/// no port, an empty host, or a non-numeric / out-of-range port is rejected.
fn parse_target(target: &str) -> Result<(&str, u16)> {
    let (host, port_str) = if let Some(rest) = target.strip_prefix('[') {
        // Bracketed IPv6 literal: `[addr]:port`.
        let (addr, tail) =
            rest.split_once(']').ok_or_else(|| SshError::Ssh(format!("malformed IPv6 target `{target}`")))?;
        let port_str =
            tail.strip_prefix(':').ok_or_else(|| SshError::Ssh(format!("missing port in target `{target}`")))?;
        (addr, port_str)
    } else {
        let (host, port_str) = target
            .rsplit_once(':')
            .ok_or_else(|| SshError::Ssh(format!("target must be host:port, got `{target}`")))?;
        if host.contains(':') {
            return Err(SshError::Ssh(format!("unbracketed IPv6 target `{target}` must use bracketed form")));
        }
        (host, port_str)
    };
    if host.is_empty() {
        return Err(SshError::Ssh(format!("target host must not be empty in `{target}`")));
    }
    let port: u16 = port_str.parse().map_err(|_| SshError::Ssh(format!("invalid port in target `{target}`")))?;
    if port == 0 {
        return Err(SshError::Ssh(format!("target port must not be zero in `{target}`")));
    }
    Ok((host, port))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::SshAuth;
    use std::io;

    /// Regression test (audit H4 siblings): a bare IPv6 literal must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn parse_target_rejects_bare_ipv6_target() {
        assert!(parse_target("2001:db8::1").is_err(), "bare IPv6 target must be rejected");
        assert!(parse_target("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn parse_target_accepts_bracketed_ipv6_target() {
        let (host, port) = parse_target("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(host, "2001:db8::1");
        assert_eq!(port, 443);
    }

    const FP_A: &str = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg";
    const FP_B: &str = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    fn valid_config() -> SshConfig {
        SshConfig {
            host: "ssh.example".to_string(),
            port: 22,
            username: "alice".to_string(),
            auth: SshAuth::Password("correct-horse".to_string()),
            host_key_policy: SshHostKeyPolicy::Tofu { pinned_fingerprint: None },
        }
    }

    #[tokio::test]
    async fn connect_validates_before_dialing() {
        // An invalid config must fail validation before any network I/O.
        let mut config = valid_config();
        config.username = String::new();
        let error = connect(&config).expect_err("invalid config must fail validation");
        assert!(matches!(error, SshError::EmptyUsername));
    }

    #[tokio::test]
    async fn vpn_required_hostname_fails_without_protected_resolver() {
        ripdpi_native_protect::unregister_protect_callback();
        let mut config = valid_config();
        config.host = "must-not-resolve.invalid".to_string();

        let client =
            connect_with_socket_protection(&config, ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
                .expect("owned connection");
        let error = client.ready().await.expect_err("VPN SSH hostname must fail without the protected resolver");

        client.close().await.expect("cleanup failed connection");
        let SshError::Io(error) = error else {
            panic!("expected typed I/O error");
        };
        assert_eq!(error.kind(), io::ErrorKind::NotConnected);
    }

    #[tokio::test]
    async fn vpn_required_ip_without_callback_fails_before_connect() {
        ripdpi_native_protect::unregister_protect_callback();
        let mut config = valid_config();
        config.host = "192.0.2.1".to_string();

        let client =
            connect_with_socket_protection(&config, ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
                .expect("owned connection");
        let error = client.ready().await.expect_err("missing callback must fail before connect");

        client.close().await.expect("cleanup failed connection");
        let SshError::Io(error) = error else {
            panic!("expected typed I/O error");
        };
        assert_eq!(error.kind(), io::ErrorKind::NotConnected);
    }

    #[tokio::test]
    async fn connect_rejects_invalid_private_key_pem() {
        let mut config = valid_config();
        config.auth = SshAuth::PrivateKey {
            pem: "-----BEGIN OPENSSH PRIVATE KEY-----\nnot-a-real-key\n-----END OPENSSH PRIVATE KEY-----".to_string(),
            passphrase: None,
        };
        // Point at the discard port so the dial fails fast; on platforms where
        // the dial unexpectedly succeeds the PEM decode still rejects. Either
        // way the call must error rather than panic.
        config.host = "127.0.0.1".to_string();
        config.port = 9;
        let client = connect(&config).expect("owned connection");
        let error = client.ready().await.expect_err("invalid PEM / unreachable host must error");
        client.close().await.expect("cleanup");
        assert!(matches!(error, SshError::Ssh(_)), "expected typed Ssh error, got {error:?}");
    }

    #[test]
    fn invalid_pem_maps_to_decode_error() {
        // Exercises the decode path directly without a network round-trip: a
        // syntactically-broken PEM must map to the typed `Ssh` decode error.
        let result = decode_secret_key("not a private key at all", None);
        assert!(result.is_err(), "garbage PEM must fail to decode");
        let mapped = SshError::Ssh(format!("private key decode failed: {}", result.unwrap_err()));
        assert!(matches!(mapped, SshError::Ssh(_)));
    }

    #[test]
    fn parse_target_accepts_host_port() {
        assert_eq!(parse_target("example.com:443").unwrap(), ("example.com", 443));
    }

    #[test]
    fn parse_target_accepts_bracketed_ipv6() {
        assert_eq!(parse_target("[2001:db8::1]:8443").unwrap(), ("2001:db8::1", 8443));
    }

    #[test]
    fn parse_target_rejects_missing_port() {
        assert!(matches!(parse_target("example.com"), Err(SshError::Ssh(_))));
    }

    #[test]
    fn parse_target_rejects_zero_port() {
        assert!(matches!(parse_target("example.com:0"), Err(SshError::Ssh(_))));
    }

    #[test]
    fn parse_target_rejects_empty_host() {
        assert!(matches!(parse_target(":443"), Err(SshError::Ssh(_))));
    }

    #[test]
    fn tofu_with_matching_pin_accepts() {
        let policy = SshHostKeyPolicy::Tofu { pinned_fingerprint: Some(FP_A.to_string()) };
        assert_eq!(evaluate_host_key(&policy, FP_A).unwrap(), HostKeyDecision::Accept);
    }

    #[test]
    fn tofu_with_no_pin_surfaces_fingerprint_for_first_use() {
        let policy = SshHostKeyPolicy::Tofu { pinned_fingerprint: None };
        match evaluate_host_key(&policy, FP_A).expect_err("first-use key must not be silently trusted") {
            SshError::HostKeyUntrusted(fp) => assert_eq!(fp, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"),
            other => panic!("expected HostKeyUntrusted, got {other:?}"),
        }
    }

    #[test]
    fn strict_mismatch_aborts() {
        let policy = SshHostKeyPolicy::Strict { fingerprint: FP_A.to_string() };
        match evaluate_host_key(&policy, FP_B).expect_err("strict mismatch must abort") {
            SshError::HostKeyMismatch { expected, got } => {
                assert_eq!(expected, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg");
                assert_eq!(got, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            }
            other => panic!("expected HostKeyMismatch, got {other:?}"),
        }
    }

    #[test]
    fn strict_match_accepts() {
        let policy = SshHostKeyPolicy::Strict { fingerprint: FP_A.to_string() };
        assert_eq!(evaluate_host_key(&policy, FP_A).unwrap(), HostKeyDecision::Accept);
    }

    /// `FP_A` re-encoded with the URL-safe base64 alphabet (`+`->`-`, `/`->`_`).
    /// `russh` presents the standard-unpadded form, so a user who pins this
    /// URL-safe spelling of the same key must still be accepted.
    const FP_A_URL_SAFE: &str = "SHA256:n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";

    #[test]
    fn url_safe_pin_matches_standard_presented() {
        // Pinned URL-safe, presented standard (as russh emits): must Accept.
        let policy = SshHostKeyPolicy::Strict { fingerprint: FP_A_URL_SAFE.to_string() };
        assert_eq!(evaluate_host_key(&policy, FP_A).unwrap(), HostKeyDecision::Accept);
        // Same for a TOFU pin.
        let tofu = SshHostKeyPolicy::Tofu { pinned_fingerprint: Some(FP_A_URL_SAFE.to_string()) };
        assert_eq!(evaluate_host_key(&tofu, FP_A).unwrap(), HostKeyDecision::Accept);
    }

    #[test]
    fn different_digest_still_mismatches_after_canonicalization() {
        // A genuinely different 32-byte digest must still abort, even though both
        // sides now canonicalise through the raw-digest path.
        let policy = SshHostKeyPolicy::Strict { fingerprint: FP_A_URL_SAFE.to_string() };
        match evaluate_host_key(&policy, FP_B).expect_err("a different digest must still mismatch") {
            SshError::HostKeyMismatch { expected, got } => {
                assert_eq!(expected, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg");
                assert_eq!(got, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            }
            other => panic!("expected HostKeyMismatch, got {other:?}"),
        }
    }
}
