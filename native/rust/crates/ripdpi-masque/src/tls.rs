use std::io;

use rustls::pki_types::{pem::PemObject, CertificateDer, PrivateKeyDer};

use crate::config::MasqueConfig;

pub(crate) fn load_client_identity(
    config: &MasqueConfig,
) -> io::Result<Option<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)>> {
    let certificate_chain = config.client_certificate_chain_pem.as_deref().filter(|value| !value.trim().is_empty());
    let private_key = config.client_private_key_pem.as_deref().filter(|value| !value.trim().is_empty());
    match (certificate_chain, private_key) {
        (Some(certificate_chain), Some(private_key)) => {
            let certificates: Vec<CertificateDer<'static>> =
                CertificateDer::pem_slice_iter(certificate_chain.as_bytes()).collect::<Result<Vec<_>, _>>().map_err(
                    |error| {
                        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid client certificate PEM: {error}"))
                    },
                )?;
            let private_key = PrivateKeyDer::from_pem_slice(private_key.as_bytes()).map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidInput, format!("invalid client private key PEM: {error}"))
            })?;
            Ok(Some((certificates, private_key)))
        }
        (None, None) => Ok(None),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "MASQUE client identity requires both a certificate chain and a private key",
        )),
    }
}

pub(crate) fn apply_h2_client_auth(
    builder: &mut boring::ssl::SslConnectorBuilder,
    config: &MasqueConfig,
) -> io::Result<()> {
    let Some(certificate_chain_pem) =
        config.client_certificate_chain_pem.as_deref().filter(|value| !value.trim().is_empty())
    else {
        return Ok(());
    };
    let private_key_pem =
        config.client_private_key_pem.as_deref().filter(|value| !value.trim().is_empty()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "MASQUE client identity requires a private key")
        })?;
    let mut certificates = boring::x509::X509::stack_from_pem(certificate_chain_pem.as_bytes()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid client certificate PEM: {error}"))
    })?;
    let leaf = certificates.first().cloned().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "missing leaf certificate in client certificate chain")
    })?;
    let private_key = boring::pkey::PKey::private_key_from_pem(private_key_pem.as_bytes()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid client private key PEM: {error}"))
    })?;
    builder
        .set_certificate(&leaf)
        .map_err(|error| io::Error::other(format!("failed to configure H2 client certificate: {error}")))?;
    builder
        .set_private_key(&private_key)
        .map_err(|error| io::Error::other(format!("failed to configure H2 client private key: {error}")))?;
    for certificate in certificates.drain(1..) {
        builder
            .add_extra_chain_cert(certificate)
            .map_err(|error| io::Error::other(format!("failed to configure H2 client certificate chain: {error}")))?;
    }
    Ok(())
}
