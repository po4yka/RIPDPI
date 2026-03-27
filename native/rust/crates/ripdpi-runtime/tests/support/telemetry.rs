use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use ripdpi_runtime::RuntimeTelemetrySink;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as TlsError, SignatureScheme};

// ── StartupLatch ──

#[derive(Default)]
pub struct StartupLatch {
    started: Mutex<bool>,
    ready: Condvar,
}

impl StartupLatch {
    pub fn mark_started(&self) {
        let mut started = self.started.lock().expect("lock startup latch");
        *started = true;
        self.ready.notify_all();
    }

    pub fn wait(&self, timeout: Duration) {
        let started = self.started.lock().expect("lock startup latch");
        let (started, _) =
            self.ready.wait_timeout_while(started, timeout, |started| !*started).expect("wait proxy startup");
        assert!(*started, "proxy listener did not report startup within {timeout:?}");
    }
}

// ── ProxyHarnessTelemetry ──

pub struct ProxyHarnessTelemetry {
    pub startup: Arc<StartupLatch>,
    pub delegate: Option<Arc<dyn RuntimeTelemetrySink>>,
}

impl RuntimeTelemetrySink for ProxyHarnessTelemetry {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize) {
        self.startup.mark_started();
        if let Some(delegate) = &self.delegate {
            delegate.on_listener_started(bind_addr, max_clients, group_count);
        }
    }

    fn on_listener_stopped(&self) {
        if let Some(delegate) = &self.delegate {
            delegate.on_listener_stopped();
        }
    }

    fn on_client_accepted(&self) {
        if let Some(delegate) = &self.delegate {
            delegate.on_client_accepted();
        }
    }

    fn on_client_finished(&self) {
        if let Some(delegate) = &self.delegate {
            delegate.on_client_finished();
        }
    }

    fn on_client_error(&self, error: &io::Error) {
        if let Some(delegate) = &self.delegate {
            delegate.on_client_error(error);
        }
    }

    fn on_failure_classified(
        &self,
        target: SocketAddr,
        failure: &ripdpi_failure_classifier::ClassifiedFailure,
        host: Option<&str>,
    ) {
        if let Some(delegate) = &self.delegate {
            delegate.on_failure_classified(target, failure, host);
        }
    }

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str) {
        if let Some(delegate) = &self.delegate {
            delegate.on_route_selected(target, group_index, host, phase);
        }
    }

    fn on_upstream_connected(&self, upstream_addr: SocketAddr, upstream_rtt_ms: Option<u64>) {
        if let Some(delegate) = &self.delegate {
            delegate.on_upstream_connected(upstream_addr, upstream_rtt_ms);
        }
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        if let Some(delegate) = &self.delegate {
            delegate.on_route_advanced(target, from_group, to_group, trigger, host);
        }
    }

    fn on_retry_paced(&self, target: SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        if let Some(delegate) = &self.delegate {
            delegate.on_retry_paced(target, group_index, reason, backoff_ms);
        }
    }

    fn on_host_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        if let Some(delegate) = &self.delegate {
            delegate.on_host_autolearn_state(enabled, learned_host_count, penalized_host_count);
        }
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        if let Some(delegate) = &self.delegate {
            delegate.on_host_autolearn_event(action, host, group_index);
        }
    }

    fn on_client_slot_exhausted(&self) {
        if let Some(delegate) = &self.delegate {
            delegate.on_client_slot_exhausted();
        }
    }

    fn on_tls_handshake_completed(&self, target: SocketAddr, latency_ms: u64) {
        if let Some(delegate) = &self.delegate {
            delegate.on_tls_handshake_completed(target, latency_ms);
        }
    }
}

// ── NoCertificateVerification ──

#[derive(Debug)]
pub struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}
