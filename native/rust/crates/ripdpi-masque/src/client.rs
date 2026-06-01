use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{Mutex, mpsc};

use crate::auth::PrivacyPassCache;
use crate::config::MasqueConfig;
use crate::h2::{attempt_h2_connect_tcp, attempt_h2_connect_tcp_over_transport};
use crate::h3::attempt_h3_connect_tcp;
use crate::migration::QuicMigrationSnapshot;
use crate::response::{AttemptError, classify_attempt_failure};
use crate::udp::MasqueUdpRelay;
use crate::validation::validate_config;

const H3_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub struct MasqueClient {
    pub(crate) inner: Arc<MasqueClientInner>,
}

pub(crate) struct MasqueClientInner {
    pub(crate) config: MasqueConfig,
    pub(crate) provider_client: reqwest::Client,
    pub(crate) privacy_pass_cache: Mutex<HashMap<String, PrivacyPassCache>>,
    pub(crate) last_migration_snapshot: Mutex<QuicMigrationSnapshot>,
}

impl MasqueClient {
    pub fn new(config: MasqueConfig) -> io::Result<Self> {
        validate_config(&config)?;
        let provider_client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|error| io::Error::other(format!("failed to build Privacy Pass provider client: {error}")))?;

        Ok(Self {
            inner: Arc::new(MasqueClientInner {
                config,
                provider_client,
                privacy_pass_cache: Mutex::new(HashMap::new()),
                last_migration_snapshot: Mutex::new(QuicMigrationSnapshot {
                    status: Some("not_attempted".to_string()),
                    reason: None,
                    cooldown_until: None,
                }),
            }),
        })
    }

    pub async fn connect(config: &MasqueConfig, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        Self::new(config.clone())?.connect_tcp(target).await
    }

    pub async fn connect_over<S>(config: &MasqueConfig, transport: S, target: &str) -> io::Result<Box<dyn AsyncIo>>
    where
        S: AsyncIo + 'static,
    {
        Self::new(config.clone())?.connect_tcp_over(transport, target).await
    }

    pub async fn connect_tcp(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        if !self.inner.config.use_http2_fallback {
            return self.connect_tcp_h3(target).await;
        }

        match tokio::time::timeout(H3_CONNECT_TIMEOUT, self.connect_tcp_h3(target)).await {
            Ok(Ok(stream)) => Ok(stream),
            Ok(Err(error)) => {
                let fallback_reason = format!("http3_connect_failed_{}", classify_attempt_failure(&error));
                tracing::info!(target, error = %error, "MASQUE H3 TCP connect failed, falling back to HTTP/2");
                match self.connect_tcp_h2(target).await {
                    Ok(stream) => {
                        self.inner.record_quic_migration_status("http2_fallback", Some(&fallback_reason)).await;
                        Ok(stream)
                    }
                    Err(fallback_error) => {
                        self.inner
                            .record_quic_migration_status(
                                "failed",
                                Some("http3_connect_failed_and_http2_fallback_failed"),
                            )
                            .await;
                        Err(fallback_error)
                    }
                }
            }
            Err(_) => {
                tracing::info!(target, "MASQUE H3 TCP connect timed out, falling back to HTTP/2");
                match self.connect_tcp_h2(target).await {
                    Ok(stream) => {
                        self.inner
                            .record_quic_migration_status("http2_fallback", Some("http3_connect_timed_out"))
                            .await;
                        Ok(stream)
                    }
                    Err(error) => {
                        self.inner
                            .record_quic_migration_status(
                                "failed",
                                Some("http3_connect_timed_out_and_http2_fallback_failed"),
                            )
                            .await;
                        Err(error)
                    }
                }
            }
        }
    }

    pub async fn connect_tcp_over<S>(&self, transport: S, target: &str) -> io::Result<Box<dyn AsyncIo>>
    where
        S: AsyncIo + 'static,
    {
        let auth_header = self.inner.request_auth_header(target).await?;
        match attempt_h2_connect_tcp_over_transport(&self.inner.config, transport, target, auth_header.as_ref()).await {
            Ok(stream) => {
                self.inner
                    .record_quic_migration_status("http2_chained", Some("http2_connect_tcp_over_entry_hop"))
                    .await;
                Ok(Box::new(stream))
            }
            Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "MASQUE Privacy Pass retry is unavailable for chained TCP exits",
            )),
            Err(AttemptError::Io(error)) => {
                self.inner.record_quic_migration_status("failed", Some("http2_chained_connect_failed")).await;
                Err(error)
            }
        }
    }

    pub fn udp_session(&self) -> MasqueUdpRelay {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        MasqueUdpRelay { client: Arc::clone(&self.inner), flows: HashMap::new(), incoming_tx, incoming_rx }
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.inner.quic_migration_snapshot()
    }

    async fn connect_tcp_h3(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        let auth_header = self.inner.request_auth_header(target).await?;
        match attempt_h3_connect_tcp(&self.inner.config, target, auth_header.as_ref()).await {
            Ok(stream) => {
                let reason = if self.inner.config.quic_migrate_after_handshake {
                    Some("path_validated_after_http3_connect")
                } else {
                    Some("http3_transport_without_rebind")
                };
                let status = if self.inner.config.quic_migrate_after_handshake { "validated" } else { "not_attempted" };
                self.inner.record_quic_migration_status(status, reason).await;
                Ok(Box::new(stream))
            }
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.inner.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h3_connect_tcp(&self.inner.config, target, Some(&retry_header)).await {
                    Ok(stream) => {
                        let reason = if self.inner.config.quic_migrate_after_handshake {
                            Some("path_validated_after_http3_connect_retry")
                        } else {
                            Some("http3_transport_without_rebind")
                        };
                        let status =
                            if self.inner.config.quic_migrate_after_handshake { "validated" } else { "not_attempted" };
                        self.inner.record_quic_migration_status(status, reason).await;
                        Ok(Box::new(stream))
                    }
                    Err(AttemptError::Io(error)) => {
                        self.inner.record_quic_migration_status("failed", Some("http3_connect_failed")).await;
                        Err(error)
                    }
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => {
                self.inner.record_quic_migration_status("failed", Some("http3_connect_failed")).await;
                Err(error)
            }
        }
    }

    async fn connect_tcp_h2(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        let auth_header = self.inner.request_auth_header(target).await?;
        match attempt_h2_connect_tcp(&self.inner.config, target, auth_header.as_ref()).await {
            Ok(stream) => Ok(Box::new(stream)),
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.inner.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h2_connect_tcp(&self.inner.config, target, Some(&retry_header)).await {
                    Ok(stream) => Ok(Box::new(stream)),
                    Err(AttemptError::Io(error)) => Err(error),
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => Err(error),
        }
    }
}
