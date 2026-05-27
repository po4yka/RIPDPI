use std::sync::Arc;

use ripdpi_tls_profiles::{EchFacadeError, EchSetup};
use tokio::net::TcpStream;
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme};
use tokio_rustls::TlsConnector;

use super::FronterError;

pub(super) fn connector(verify_ssl: bool) -> TlsConnector {
    let tls_config =
        client_config_with_ech_setup(verify_ssl, &EchSetup::Grease).expect("Apps Script TLS config must support ECH");

    TlsConnector::from(Arc::new(tls_config))
}

pub(super) fn client_config_with_ech_setup(verify_ssl: bool, setup: &EchSetup) -> Result<ClientConfig, EchFacadeError> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let builder = ripdpi_tls_profiles::configure_rustls_ech(
        ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into()),
        setup,
    )?;
    let tls_config = if verify_ssl {
        builder.with_root_certificates(roots).with_no_client_auth()
    } else {
        builder.dangerous().with_custom_certificate_verifier(Arc::new(NoVerify)).with_no_client_auth()
    };

    Ok(tls_config)
}

pub(super) async fn connect_fronted_stream(
    connector: &TlsConnector,
    connect_host: &str,
    front_domain: &str,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
    let stream = TcpStream::connect((connect_host, 443)).await?;
    stream.set_nodelay(true)?;
    let server_name = ServerName::try_from(front_domain.to_string())?;
    Ok(connector.connect(server_name, stream).await?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_tls_profiles::EchSetup;

    #[test]
    fn apps_script_tls_config_uses_ech_facade_grease_for_front_domain() {
        let config = client_config_with_ech_setup(true, &EchSetup::Grease).expect("ECH fronting TLS config");

        assert!(rustls_client_hello_has_ech_extension(config), "Apps Script fronting TLS must send ECH or GREASE");
    }

    fn rustls_client_hello_has_ech_extension(config: rustls::ClientConfig) -> bool {
        let server_name = rustls::pki_types::ServerName::try_from("www.google.com").expect("server name");
        let mut conn = rustls::ClientConnection::new(std::sync::Arc::new(config), server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d)
    }
}

#[derive(Debug)]
struct NoVerify;

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}
