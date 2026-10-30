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
    let mut config = ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
        .expect("bundled webpki roots are non-empty")
        .with_no_client_auth();
    // The relay speaks HTTP/1.1 to the front; a ClientHello without ALPN is unlike any browser's.
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(config)
}

pub(super) async fn connect_fronted_stream(
    connector: &TlsConnector,
    connect_host: &str,
    port: u16,
    front_domain: &str,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
    // PROTECT INVARIANT: no `protect_socket(fd)` is required here. The Apps Script
    // domain-fronter runs only inside the relay native session, which serves a loopback
    // SOCKS listener in proxy mode and owns no TUN device (see
    // `ripdpi-relay-android/src/lib.rs` §"Idempotency, fds, errors": "it serves a loopback
    // SOCKS listener and owns its transport sockets"; the relay registers no VPN-protect
    // callback). With no TUN to capture outbound traffic, this front connection cannot loop
    // back into the tunnel, so the `vpnservice-protect-invariant.md` rule does not apply.
    let stream = TcpStream::connect((connect_host, port)).await?;
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

        assert!(client_hello_extension(config, 0xfe0d).is_some(), "Apps Script fronting TLS must send ECH or GREASE");
    }

    #[test]
    fn apps_script_tls_config_offers_http11_alpn_like_a_browser_fetch() {
        let config = client_config_with_ech_setup(&EchSetup::Grease).expect("ECH fronting TLS config");

        // ALPN extension body: list length 9, then one 8-byte protocol name.
        assert_eq!(client_hello_extension(config, 0x0010).as_deref(), Some(&b"\x00\x09\x08http/1.1"[..]));
    }

    /// The data of extension `ext_type` in the ClientHello `config` sends to the front domain.
    fn client_hello_extension(config: rustls::ClientConfig, ext_type: u16) -> Option<Vec<u8>> {
        let server_name = rustls::pki_types::ServerName::try_from("www.google.com").expect("server name");
        let mut conn = rustls::ClientConnection::new(std::sync::Arc::new(config), server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        let extension = layout.extensions.iter().find(|extension| extension.ext_type == ext_type)?;
        Some(bytes[extension.data_offset..extension.data_offset + extension.data_len].to_vec())
    }
}
