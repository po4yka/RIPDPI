use std::net::IpAddr;
use std::sync::Arc;

use boring::ssl::{SslConnector, SslConnectorBuilder};
use boring::x509::{X509, X509StoreContextRef, X509VerifyError};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio::time::{Instant, timeout};

use super::EncryptedDnsResolver;
use super::connection::{DotTlsStream, MAX_POOLED_IDLE_DURATION};
use super::dot_connection::DotConnection;
use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsError};

impl EncryptedDnsResolver {
    /// Sends the query on the shared DoT connection. When a reused connection
    /// turns out broken, the query is retried once on a fresh one (RFC 7766 6.2.3).
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: [`DotConnection::exchange`] is cancel-safe, and a cancelled
    /// connect stores no connection.
    pub(super) async fn exchange_dot(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let (connection, reused) = self.dot_connection().await?;
        // A connection idle for longer than one timeout may have died silently
        // (NAT expiry, network handover); give it half so the retry still fits.
        let limit = if reused && connection.idle_for() > self.inner.timeout {
            self.inner.timeout / 2
        } else {
            self.inner.timeout
        };
        match connection.exchange(query_bytes, limit).await {
            Err(_) if reused && connection.is_broken() => {
                let (fresh, _) = self.dot_connection().await?;
                fresh.exchange(query_bytes, self.inner.timeout).await
            }
            result => result,
        }
    }

    /// The usable shared connection, or a new one. Concurrent callers wait for one connect.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the slot keeps the old value until a new connection is ready.
    async fn dot_connection(&self) -> Result<(Arc<DotConnection>, bool), EncryptedDnsError> {
        let mut current = self.inner.dot_connection.lock().await;
        if let Some(connection) = current.as_ref().filter(|connection| connection.usable(MAX_POOLED_IDLE_DURATION)) {
            return Ok((connection.clone(), true));
        }
        let connection = Arc::new(DotConnection::new(self.connect_dot_session().await?));
        *current = Some(connection.clone());
        Ok((connection, false))
    }

    /// Tries each bootstrap IP through TCP and TLS, so a TLS-stage failure moves
    /// on to the next IP (RFC 7858 3.1).
    ///
    /// # Cancel safety:
    /// cancel-safe: this call owns the socket and TLS stream until validation succeeds.
    async fn connect_dot_session(&self) -> Result<DotTlsStream, EncryptedDnsError> {
        if self.inner.dot_unpinned_custom_verifier_requested {
            return Err(EncryptedDnsError::Tls(
                "DoT custom TLS verifier requires a pinned SPKI hash or pinned certificate".to_string(),
            ));
        }
        let connector = self.dot_tls_connector()?;
        let ips = self.ranked_bootstrap_ips();
        // Over SOCKS5 without bootstrap IPs the proxy resolves the host: one attempt.
        let candidates: Vec<Option<IpAddr>> =
            if ips.is_empty() { vec![None] } else { ips.into_iter().map(Some).collect() };
        let mut last_error = None;
        for ip in candidates {
            let started = Instant::now();
            // The TCP stage records only its failures; one outcome per IP covers TCP and TLS.
            let tcp_stream = match self.connect_plain_tcp_to(ip.into_iter().collect(), false).await {
                Ok(stream) => stream,
                Err(error) => {
                    last_error = Some(error);
                    continue;
                }
            };
            let result = self.dot_tls_handshake(&connector, tcp_stream).await;
            if let Some(ip) = ip {
                self.record_bootstrap_outcome(ip, result.is_ok(), started.elapsed());
            }
            match result {
                Ok(stream) => return Ok(stream),
                Err(error) => last_error = Some(error),
            }
        }
        Err(last_error.unwrap_or(EncryptedDnsError::MissingBootstrapIps))
    }

    /// The DoT TLS configuration; it does not depend on the bootstrap IP.
    fn dot_tls_connector(&self) -> Result<SslConnector, EncryptedDnsError> {
        let mut builder = build_dot_connector_builder(&self.inner.connect_hooks)?;
        builder
            .set_alpn_protos(b"\x03dot")
            .map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS ALPN: {error}")))?;
        for root_der in &self.inner.dot_extra_roots {
            let x509 = X509::from_der(root_der.as_ref())
                .map_err(|e| EncryptedDnsError::Tls(format!("DoT extra root cert: {e}")))?;
            builder
                .cert_store_mut()
                .add_cert(x509)
                .map_err(|e| EncryptedDnsError::Tls(format!("DoT add root cert: {e}")))?;
        }
        if let Some(verifier) = &self.inner.dot_pin_verifier {
            configure_dot_pin_callback(&mut builder, self.dot_tls_name(), verifier.clone())?;
        }
        Ok(builder.build())
    }

    fn dot_tls_name(&self) -> String {
        self.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| self.inner.endpoint.host.clone())
    }

    /// # Cancel safety:
    /// cancel-safe: this call owns the TLS stream until validation succeeds.
    async fn dot_tls_handshake(
        &self,
        connector: &SslConnector,
        tcp_stream: tokio::net::TcpStream,
    ) -> Result<DotTlsStream, EncryptedDnsError> {
        let tls_name = self.dot_tls_name();
        let config_ssl =
            connector.configure().map_err(|e| EncryptedDnsError::Tls(format!("DoT TLS configure: {e}")))?;
        match timeout(self.inner.timeout, tokio_boring::connect(config_ssl, &tls_name, tcp_stream)).await {
            Ok(Ok(stream)) => match stream.ssl().selected_alpn_protocol() {
                None | Some(b"dot") => Ok(stream),
                Some(_) => Err(EncryptedDnsError::Tls("DoT TLS negotiated an unexpected ALPN protocol".to_string())),
            },
            Ok(Err(err)) => Err(EncryptedDnsError::Tls(format!("DoT TLS handshake to {tls_name}: {err}"))),
            Err(_) => Err(EncryptedDnsError::Tls(format!("DoT TLS handshake to {tls_name} timed out"))),
        }
    }
}

fn build_dot_connector_builder(hooks: &EncryptedDnsConnectHooks) -> Result<SslConnectorBuilder, EncryptedDnsError> {
    if let Some(factory) = &hooks.dot_tls_connector_builder {
        return factory().map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS builder: {error}")));
    }

    let builder = ripdpi_tls_profiles::configure_builder("chrome_stable")
        .map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS builder: {error}")))?;
    Ok(builder)
}

fn configure_dot_pin_callback(
    builder: &mut SslConnectorBuilder,
    tls_name: String,
    verifier: Arc<dyn rustls::client::danger::ServerCertVerifier>,
) -> Result<(), EncryptedDnsError> {
    let server_name = ServerName::try_from(tls_name.clone())
        .map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS server name {tls_name}: {error}")))?;
    builder.set_cert_verify_callback(move |context| {
        let accepted = dot_pin_verifier_accepts(context, &verifier, &server_name);
        if !accepted {
            context.set_error(Err(X509VerifyError::CERT_REJECTED));
        }
        accepted
    });
    Ok(())
}

fn dot_pin_verifier_accepts(
    context: &mut X509StoreContextRef,
    verifier: &Arc<dyn rustls::client::danger::ServerCertVerifier>,
    server_name: &ServerName<'static>,
) -> bool {
    let Some(end_entity) = context.cert().and_then(|cert| cert.to_der().ok()).map(CertificateDer::from) else {
        return false;
    };
    let intermediates = context
        .untrusted()
        .map(|chain| {
            chain
                .iter()
                .skip(1)
                .filter_map(|certificate| certificate.to_der().ok().map(CertificateDer::from))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if verifier.verify_server_cert(&end_entity, &intermediates, server_name, &[], UnixTime::now()).is_ok() {
        context.set_error(Ok(()));
        true
    } else {
        false
    }
}
