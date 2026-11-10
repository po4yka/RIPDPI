//! Independent upstream-faithful ShadowTLS v3 server oracle.
//!
//! This module re-derives the v3 server from ihciah/shadow-tls `src/server.rs`
//! and `src/util.rs` at commit 02dd0bc7bae8a2011729f95021690e694fd8e43e. It uses
//! `ring` HMAC and SHA-256 directly. It must not reuse the crate helpers
//! (`hmac`, `frames`, `handshake`): a bug shared by the client and the helpers
//! would then pass unseen. `ShadowTlsLoopback` reuses those helpers and does
//! not verify the ClientHello HMAC, so it cannot play this role. The cover
//! server is a real rustls TLS 1.3 `ServerConnection`. The client side uses
//! only the public `ShadowTlsClient` and `Config` API.
#![allow(clippy::unwrap_used, clippy::expect_used)]
use std::sync::Arc;

use ring::digest::{SHA256, digest};
use ring::hmac::{Context, HMAC_SHA1_FOR_LEGACY_USE_ONLY, Key};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream};

use crate::{Config, ShadowTlsClient};

const PW: &str = "audit-password";

fn ctx(pw: &[u8], parts: &[&[u8]]) -> Context {
    let mut c = Context::with_key(&Key::new(HMAC_SHA1_FOR_LEGACY_USE_ONLY, pw));
    for p in parts {
        c.update(p);
    }
    c
}
fn tag(c: &Context) -> [u8; 4] {
    let t = c.clone().sign();
    [t.as_ref()[0], t.as_ref()[1], t.as_ref()[2], t.as_ref()[3]]
}

async fn read_record<S: AsyncRead + Unpin>(s: &mut S) -> std::io::Result<Vec<u8>> {
    let mut h = [0u8; 5];
    s.read_exact(&mut h).await?;
    let n = u16::from_be_bytes([h[3], h[4]]) as usize;
    let mut r = h.to_vec();
    r.resize(5 + n, 0);
    s.read_exact(&mut r[5..]).await?;
    Ok(r)
}

fn split_records(mut b: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    while b.len() >= 5 {
        let n = 5 + u16::from_be_bytes([b[3], b[4]]) as usize;
        out.push(b[..n].to_vec());
        b = &b[n..];
    }
    out
}

/// upstream server.rs ClientHello check, independently re-derived:
/// HMAC_SHA1(pw, handshake msg with the last 4 session-id bytes zeroed)[..4].
fn upstream_verify_client_hello(pw: &[u8], record: &[u8]) -> bool {
    let msg = &record[5..];
    if msg.len() < 71 || msg[0] != 1 || msg[38] != 32 {
        return false;
    }
    let mut tmp = msg.to_vec();
    let got = [tmp[67], tmp[68], tmp[69], tmp[70]];
    tmp[67..71].fill(0);
    let c = ctx(pw, &[&tmp]);
    tag(&c) == got
}

fn appdata(tag4: [u8; 4], payload: &[u8]) -> Vec<u8> {
    let mut f = vec![0x17, 0x03, 0x03];
    f.extend_from_slice(&((payload.len() + 4) as u16).to_be_bytes());
    f.extend_from_slice(&tag4);
    f.extend_from_slice(payload);
    f
}

fn cover_config(only_p256: bool) -> Arc<rustls::ServerConfig> {
    let cert = rcgen::generate_simple_self_signed(vec!["cover.example".to_string()]).unwrap();
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());
    let mut provider = rustls::crypto::ring::default_provider();
    if only_p256 {
        provider.kx_groups = vec![rustls::crypto::ring::kx_group::SECP256R1];
    }
    Arc::new(
        rustls::ServerConfig::builder_with_provider(provider.into())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der], key_der.into())
            .unwrap(),
    )
}

#[derive(Debug, Default)]
struct ServerReport {
    client_hello_hmac_ok: bool,
    cover_records_after_server_hello: usize,
    residual_frames_sent: usize,
    client_frames_forwarded_to_cover: usize,
    switched: bool,
}

async fn upstream_like_server(mut s: DuplexStream, server_pw: &str, only_p256: bool) -> ServerReport {
    let pw = server_pw.as_bytes();
    let mut rep = ServerReport::default();
    let Ok(ch) = read_record(&mut s).await else { return rep };
    rep.client_hello_hmac_ok = upstream_verify_client_hello(pw, &ch);
    let mut cover = rustls::ServerConnection::new(cover_config(only_p256)).unwrap();
    cover.read_tls(&mut std::io::Cursor::new(&ch)).unwrap();
    cover.process_new_packets().unwrap();
    let mut flight = Vec::new();
    while cover.wants_write() {
        cover.write_tls(&mut flight).unwrap();
    }
    let recs = split_records(&flight);
    if !rep.client_hello_hmac_ok {
        // upstream: "ClientHello verify failed, will work as a SNI proxy"
        for r in &recs {
            let _ = s.write_all(r).await;
        }
        let mut sink = [0u8; 256];
        while let Ok(n) = s.read(&mut sink).await {
            if n == 0 {
                break;
            }
        }
        return rep;
    }
    let sh = &recs[0];
    let sr = sh[11..43].to_vec();
    s.write_all(sh).await.unwrap();
    let key = digest(&SHA256, &[pw, &sr].concat());
    let mut hmac_sr = ctx(pw, &[&sr]);
    let mut hmac_c = ctx(pw, &[&sr, b"C"]);
    let mut hmac_s = ctx(pw, &[&sr, b"S"]);
    let modify = |hmac_sr: &mut Context, rec: &[u8]| -> Vec<u8> {
        let mut p = rec[5..].to_vec();
        for (i, b) in p.iter_mut().enumerate() {
            *b ^= key.as_ref()[i % key.as_ref().len()];
        }
        hmac_sr.update(&p);
        appdata(tag(hmac_sr), &p)
    };
    // copy_by_frame_with_modification: relay the rest of the cover flight.
    for r in &recs[1..] {
        rep.cover_records_after_server_hello += 1;
        let out = if r[0] == 0x17 { modify(&mut hmac_sr, r) } else { r.clone() };
        s.write_all(&out).await.unwrap();
    }
    // Extra in-flight cover record (e.g. a late NewSessionTicket-sized record)
    // that must be ignored by the client's residual detector.
    let late = {
        let mut r = vec![0x17, 0x03, 0x03, 0x00, 0x30];
        r.extend((0..0x30u8).map(|i| i.wrapping_mul(7)));
        r
    };
    s.write_all(&modify(&mut hmac_sr, &late)).await.unwrap();
    rep.residual_frames_sent = rep.cover_records_after_server_hello + 1;

    // copy_by_frame_until_hmac_matches (client -> cover).
    let first_data = loop {
        let Ok(f) = read_record(&mut s).await else { return rep };
        if f.len() > 9 && f[0] == 0x17 {
            let mut t = hmac_c.clone();
            t.update(&f[9..]);
            if f[5..9] == tag(&t) {
                hmac_c.update(&f[9..]);
                hmac_c.update(&f[5..9]);
                break f[9..].to_vec();
            }
        }
        rep.client_frames_forwarded_to_cover += 1;
        let _ = cover.read_tls(&mut std::io::Cursor::new(&f));
        let _ = cover.process_new_packets();
    };
    rep.switched = true;
    // verified_relay with an echo "data server".
    let echo = |data: &[u8], hmac_s: &mut Context| -> Vec<u8> {
        hmac_s.update(data);
        let t = tag(hmac_s);
        hmac_s.update(&t);
        appdata(t, data)
    };
    s.write_all(&echo(&first_data, &mut hmac_s)).await.unwrap();
    loop {
        let Ok(f) = read_record(&mut s).await else { break };
        if f[0] != 0x17 {
            break;
        }
        hmac_c.update(&f[9..]);
        let t = tag(&hmac_c);
        if f[5..9] != t {
            break;
        }
        hmac_c.update(&t);
        let reply = echo(&f[9..], &mut hmac_s);
        if s.write_all(&reply).await.is_err() {
            break;
        }
    }
    rep
}

fn client(pw: &str) -> ShadowTlsClient {
    ShadowTlsClient::new(Config {
        password: pw.to_string(),
        server_name: "cover.example".to_string(),
        inner_profile_id: "inner".to_string(),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
        outbound_bind_ip: None,
    })
}

async fn roundtrip<S: AsyncRead + AsyncWrite + Unpin>(s: &mut S, msg: &[u8]) -> Vec<u8> {
    s.write_all(msg).await.unwrap();
    s.flush().await.unwrap();
    let mut buf = vec![0u8; msg.len()];
    s.read_exact(&mut buf).await.unwrap();
    buf
}

/// # Cancel safety
///
/// cancel-safe: the in-memory duplex halves are owned by the test.
#[tokio::test]
async fn upstream_faithful_server_round_trip_with_residual_frames() {
    let (a, b) = tokio::io::duplex(1 << 20);
    let server = tokio::spawn(upstream_like_server(b, PW, false));
    let mut stream = client(PW).connect_over(a).await.expect("handshake with upstream-faithful server");
    for i in 0..50u32 {
        let msg: Vec<u8> = (0..(i * 997 % 40_000 + 1)).map(|j| (j as u8) ^ (i as u8)).collect();
        assert_eq!(roundtrip(&mut stream, &msg).await, msg, "payload {i}");
    }
    drop(stream);
    let rep = server.await.unwrap();
    assert!(rep.client_hello_hmac_ok, "independent ClientHello HMAC check");
    assert!(rep.switched);
    assert!(rep.residual_frames_sent >= 2, "residual frames exercised");
}

/// # Cancel safety
///
/// cancel-safe: the in-memory duplex halves are owned by the test.
#[tokio::test]
async fn wrong_password_fails_closed() {
    let (a, b) = tokio::io::duplex(1 << 20);
    let server = tokio::spawn(upstream_like_server(b, "other-password", false));
    let err = client(PW).connect_over(a).await.err().expect("wrong password must fail");
    assert!(err.to_string().contains("HMAC"), "unexpected error: {err}");
    let rep = server.await.unwrap();
    assert!(!rep.client_hello_hmac_ok);
    assert!(!rep.switched);
}

/// # Cancel safety
///
/// cancel-safe: the in-memory duplex halves are owned by the test.
#[tokio::test]
async fn cover_hello_retry_request_is_a_typed_error() {
    let (a, b) = tokio::io::duplex(1 << 20);
    let server = tokio::spawn(upstream_like_server(b, PW, true));
    let err = client(PW).connect_over(a).await.err().expect("HelloRetryRequest must fail");
    let typed = err
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<crate::ShadowTlsHandshakeError>())
        .unwrap_or_else(|| panic!("untyped error: {err}"));
    assert_eq!(typed.kind(), crate::ShadowTlsFailureKind::CoverHelloRetry);
    drop(server.await);
}
