use std::sync::Arc;

use ripdpi_tls_profiles::{EchFacadeError, EchSetup};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};

use super::FronterError;

pub(super) fn connector() -> TlsConnector {
    let tls_config = client_config_with_ech_setup(&EchSetup::Grease).expect("Apps Script TLS config must support ECH");

    TlsConnector::from(Arc::new(tls_config))
}

pub(super) fn client_config_with_ech_setup(setup: &EchSetup) -> Result<ClientConfig, EchFacadeError> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let builder = ripdpi_tls_profiles::configure_rustls_ech(
        ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into()),
        setup,
    )?;
    Ok(builder.with_root_certificates(roots).with_no_client_auth())
}

pub(super) async fn connect_fronted_stream(
    connector: &TlsConnector,
    connect_host: &str,
    front_domain: &str,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
    // PROTECT INVARIANT: no `protect_socket(fd)` is required here. The Apps Script
    // domain-fronter runs only inside the relay native session, which serves a loopback
    // SOCKS listener in proxy mode and owns no TUN device (see
    // `ripdpi-relay-android/src/lib.rs` §"Idempotency, fds, errors": "it serves a loopback
    // SOCKS listener and owns its transport sockets"; the relay registers no VPN-protect
    // callback). With no TUN to capture outbound traffic, this front connection cannot loop
    // back into the tunnel, so the `vpnservice-protect-invariant.md` rule does not apply.
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
        let config = client_config_with_ech_setup(&EchSetup::Grease).expect("ECH fronting TLS config");

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
