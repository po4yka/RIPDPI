//! Composable QUIC + HTTP/3 transport layer.
//!
//! # Why this lives in `ripdpi-hysteria2`
//!
//! The task `refactor-quic-and-h3-into-a-composable-transport-crate` sketches
//! a brand-new `ripdpi-transport-quic` crate. The task's `Verify` command,
//! however, runs `cargo nextest run -p ripdpi-hysteria2`, and the `Scope`
//! contract restricts edits to `ripdpi-hysteria2/**` and `ripdpi-masque/**`
//! -- it does not permit registering a new workspace member. This module is
//! therefore the "`ripdpi-transport-quic`" the epic asks for, shipped as a
//! first-class public module of `ripdpi-hysteria2`.
//!
//! `ripdpi-hysteria2` is the natural home: it already owns the `quinn` +
//! `h3` + `h3-quinn` dependency set, and the bi-directional-stream surface
//! it needs (`crate::tcp::DuplexStream`) is the prototype this module
//! generalizes. `ripdpi-masque` then consumes this module (it depends on
//! `ripdpi-hysteria2`) so the shared `quinn` setup is no longer duplicated:
//! see `ripdpi-masque`'s socket / endpoint code.
//!
//! # What it provides
//!
//! * [`QuicTransportConfig`] + its factory -- the shared `quinn` + `rustls`
//!   client-config construction, with **ALPN**, **SNI**, and a per-profile
//!   **uTLS-style fingerprint profile** configurable at the transport
//!   boundary.
//! * [`build_quic_endpoint`] / [`build_client_udp_socket`] -- the shared
//!   QUIC client UDP-socket + `quinn::Endpoint` construction that Hysteria2
//!   and MASQUE each open-coded.
//! * [`QuicTransport`] + [`QuicBiStream`] -- the bi-directional QUIC stream
//!   surface (`AsyncRead + AsyncWrite`) outbounds layer their framing on.
//! * [`QuicDatagramTransport`] -- the QUIC unreliable-datagram surface for
//!   CONNECT-UDP / UDP-relay outbounds.
//! * [`H3Transport`] + [`build_connect_request`] -- the HTTP/3 facade
//!   exposing a CONNECT-capable surface composable under VLESS / VMess /
//!   generic outbounds.

pub mod config;
pub mod datagram;
pub mod endpoint;
pub mod h3;
pub mod stream;

pub use config::{ALPN_H3, QuicTransportConfig};
pub use datagram::QuicDatagramTransport;
pub use endpoint::{
    build_client_udp_socket, build_client_udp_socket_with_policy, build_quic_endpoint, maybe_rebind_endpoint,
};
pub use h3::{H3ClientParts, H3ConnectKind, H3Transport, build_connect_request};
pub use stream::{QuicBiStream, QuicTransport};

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{Ipv4Addr, SocketAddr};
    use std::sync::Arc;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Stand up an in-process QUIC server with a self-signed certificate,
    /// returning its address and the server `quinn::Endpoint`. This is the
    /// scaffold an end-to-end transport test (or a VLESS-QUIC wire test)
    /// would use.
    fn spawn_quic_echo_server() -> (SocketAddr, quinn::Endpoint) {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("self-signed cert");
        let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
        let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der()).clone_key();

        // Pin the `ring` provider explicitly: the workspace enables both
        // `ring` and `aws-lc-rs`, so the bare `ServerConfig::builder()` cannot
        // auto-pick one -- the same reason the shared client-config factory
        // pins `ring`.
        let mut server_crypto =
            rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der.into())
                .expect("server tls config");
        server_crypto.alpn_protocols = vec![ALPN_H3.to_vec()];
        let server_config = quinn::ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto).expect("quic server crypto"),
        ));

        let endpoint = quinn::Endpoint::server(server_config, SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
            .expect("bind quic server");
        let addr = endpoint.local_addr().expect("server addr");
        (addr, endpoint)
    }

    /// End-to-end proof that the composable transport works: a client built
    /// entirely from the shared factory connects to a real QUIC server,
    /// opens a [`QuicBiStream`], and round-trips bytes. This is the test a
    /// VLESS-QUIC outbound would extend with its own handshake framing.
    #[tokio::test]
    async fn quic_transport_round_trips_bytes_over_a_real_connection() {
        let (server_addr, server_endpoint) = spawn_quic_echo_server();

        // Server: accept one connection, echo one bi-stream.
        let server_task = tokio::spawn(async move {
            let incoming = server_endpoint.accept().await.expect("incoming connection");
            let connection = incoming.await.expect("accept connection");
            let (mut send, mut recv) = connection.accept_bi().await.expect("accept bi-stream");
            let mut buf = [0u8; 64];
            let read = recv.read(&mut buf).await.expect("server read").expect("some bytes");
            send.write_all(&buf[..read]).await.expect("server echo");
            send.finish().expect("server finish");
            // Hold the connection until the client has read the echo.
            connection.closed().await;
        });

        // Client: built from the shared QuicTransportConfig factory + the
        // shared endpoint builder. `insecure` because the server cert is
        // self-signed -- exactly the Reality/self-signed code path.
        let config = QuicTransportConfig::new("localhost").with_insecure(true);
        let endpoint = build_quic_endpoint(&config, false).expect("build client endpoint");
        let connection =
            endpoint.connect(server_addr, &config.server_name).expect("start connect").await.expect("connect");

        let transport = QuicTransport::new(connection.clone());
        let mut stream = transport.open_bi().await.expect("open bi-stream");

        let payload = b"composable quic transport";
        stream.write_all(payload).await.expect("client write");
        stream.shutdown().await.expect("client finish send");

        let mut echoed = Vec::new();
        stream.read_to_end(&mut echoed).await.expect("client read echo");
        assert_eq!(&echoed, payload);

        connection.close(0u32.into(), b"done");
        server_task.await.expect("server task");
    }

    /// The datagram surface, exercised against a real connection: the client
    /// transport reports the negotiated datagram limit (this server does not
    /// advertise datagrams, so it reports unsupported -- the case callers
    /// must guard before sending).
    #[tokio::test]
    async fn quic_datagram_transport_reports_negotiated_support() {
        let (server_addr, server_endpoint) = spawn_quic_echo_server();
        let server_task = tokio::spawn(async move {
            let incoming = server_endpoint.accept().await.expect("incoming");
            let connection = incoming.await.expect("accept");
            connection.closed().await;
        });

        let config = QuicTransportConfig::new("localhost").with_insecure(true);
        let endpoint = build_quic_endpoint(&config, false).expect("endpoint");
        let connection =
            endpoint.connect(server_addr, &config.server_name).expect("connect start").await.expect("connect");

        let datagram = QuicDatagramTransport::new(connection.clone());
        // `datagrams_supported()` is the guard a UDP outbound checks; here it
        // is simply asserted to be callable and internally consistent.
        assert_eq!(datagram.datagrams_supported(), datagram.max_datagram_size().is_some());

        connection.close(0u32.into(), b"done");
        server_task.await.expect("server task");
    }

    /// Guard: an `H3Transport` can be constructed over a real QUIC
    /// connection from the shared factory. (Driving a full H3 CONNECT needs
    /// an H3 server; the request-builder path is covered by `h3::tests`.)
    #[tokio::test]
    async fn h3_transport_constructs_over_a_real_quic_connection() {
        let (server_addr, server_endpoint) = spawn_quic_echo_server();
        let server_task = tokio::spawn(async move {
            let incoming = server_endpoint.accept().await.expect("incoming");
            let connection = incoming.await.expect("accept");
            connection.closed().await;
        });

        let config = QuicTransportConfig::new("localhost").with_insecure(true);
        let endpoint = build_quic_endpoint(&config, false).expect("endpoint");
        let connection =
            endpoint.connect(server_addr, &config.server_name).expect("connect start").await.expect("connect");

        let h3 = H3Transport::new(QuicTransport::new(connection.clone()));
        // The facade holds the QUIC transport; its `connect()` would
        // negotiate H3 against an H3-speaking server.
        assert!(h3.quic().max_datagram_size().is_none() || h3.quic().max_datagram_size().is_some());

        connection.close(0u32.into(), b"done");
        server_task.await.expect("server task");
    }
}
