mod handshake;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::{DomainTarget, ProbeDetail};

use self::handshake::retry_tls13_handshake;

pub(super) struct HttpsRetryDecision {
    pub(super) final_outcome: String,
}

pub(super) fn apply_https_retry_policy(
    transport: &TransportConfig,
    target: &DomainTarget,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    https_port: u16,
    outcome: &str,
    details: &mut Vec<ProbeDetail>,
) -> HttpsRetryDecision {
    if outcome == "tls_handshake_failed" {
        let retry = retry_tls13_handshake(transport, target, tls_verifier, key_log, https_port);
        let retry_outcome = if retry.status == "tls_ok" { "tls_ok" } else { "tls_handshake_failed" };
        details.push(ProbeDetail { key: "retryOutcome".to_string(), value: retry_outcome.to_string() });
        details.push(ProbeDetail {
            key: "retryError".to_string(),
            value: retry.error.unwrap_or_else(|| "none".to_string()),
        });
        details
            .push(ProbeDetail { key: "retryOnlySuccess".to_string(), value: (retry_outcome == "tls_ok").to_string() });
        details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: "1".to_string() });
        let final_outcome = finalize_https_retry_outcome(outcome, retry_outcome).to_string();

        HttpsRetryDecision { final_outcome }
    } else {
        details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: "0".to_string() });
        HttpsRetryDecision { final_outcome: outcome.to_string() }
    }
}

fn finalize_https_retry_outcome<'a>(initial_outcome: &'a str, _retry_outcome: &str) -> &'a str {
    initial_outcome
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::sync::Arc;
    use std::thread::JoinHandle;

    use rustls::pki_types::PrivateKeyDer;
    use rustls::{ServerConfig, ServerConnection};

    use crate::tls::NoCertificateVerification;
    use crate::transport::TransportConfig;
    use crate::types::{DomainTarget, ProbeDetail};

    use super::apply_https_retry_policy;

    #[test]
    fn retry_only_success_is_not_promoted_to_stable_tls_success() {
        let server = TlsHandshakeServer::spawn();
        let target = DomainTarget {
            host: "localhost".to_string(),
            connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
            connect_ips: Vec::new(),
            https_port: Some(server.addr.port()),
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };
        let transport = TransportConfig::Direct { route_experiment: None };
        let verifier: Arc<dyn rustls::client::danger::ServerCertVerifier> = Arc::new(NoCertificateVerification);
        let mut details = Vec::<ProbeDetail>::new();

        let decision = apply_https_retry_policy(
            &transport,
            &target,
            Some(&verifier),
            None,
            server.addr.port(),
            "tls_handshake_failed",
            &mut details,
        );

        assert_eq!(decision.final_outcome, "tls_handshake_failed");
        assert_eq!(detail_value(&details, "retryOutcome"), "tls_ok");
        assert_eq!(detail_value(&details, "retryOnlySuccess"), "true");
        assert_eq!(detail_value(&details, "probeRetryCount"), "1");
        server.join();
    }

    fn detail_value<'a>(details: &'a [ProbeDetail], key: &str) -> &'a str {
        details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str()).expect("detail exists")
    }

    struct TlsHandshakeServer {
        addr: SocketAddr,
        handle: JoinHandle<()>,
    }

    impl TlsHandshakeServer {
        fn spawn() -> Self {
            let certificate =
                rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("self-signed certificate");
            let cert_der = certificate.cert.der().clone();
            let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
            let config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der)
                .expect("server config");
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
            let addr = listener.local_addr().expect("fixture addr");
            let handle = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().expect("accept fixture connection");
                let mut connection = ServerConnection::new(Arc::new(config)).expect("server connection");
                while connection.is_handshaking() {
                    connection.complete_io(&mut socket).expect("server handshake");
                }
            });

            Self { addr, handle }
        }

        fn join(self) {
            self.handle.join().expect("fixture thread");
        }
    }
}
