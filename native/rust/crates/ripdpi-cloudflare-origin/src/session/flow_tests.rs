use std::io;
use std::time::Duration;

use bytes::Bytes;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;

use super::run_session;

const IDENTITY: [u8; 16] = [7; 16];

/// # Cancel safety:
/// cancel-safe: each case owns its channels, listener and session future.
#[tokio::test(flavor = "current_thread")]
async fn nonempty_wire_flows_do_not_authenticate_respond_or_connect() {
    for flow in ["xtls-rprx-vision", "xtls-rprx-vision-udp443", "unsupported-flow", "none", "off", " "] {
        let mut addons = vec![0x0a, u8::try_from(flow.len()).expect("short Flow")];
        addons.extend_from_slice(flow.as_bytes());
        rejects_before_target_effects(&addons, IDENTITY, io::ErrorKind::Unsupported).await;
    }
}

/// # Cancel safety:
/// cancel-safe: this test owns the channels, listener and session future.
#[tokio::test(flavor = "current_thread")]
async fn wrong_uuid_remains_rejected_before_target_effects() {
    rejects_before_target_effects(&[], [8; 16], io::ErrorKind::PermissionDenied).await;
}

/// # Cancel safety:
/// cancel-safe: cancellation drops both joined futures and all owned session resources.
async fn rejects_before_target_effects(addons: &[u8], uuid: [u8; 16], kind: io::ErrorKind) {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind target");
    let target = listener.local_addr().expect("target address").to_string();
    let (post, inbound) = mpsc::channel(1);
    let (outbound, mut download) = mpsc::channel(1);
    let header = ripdpi_vless::wire::encode_request(&uuid, addons, &target).expect("VLESS request");
    post.send(Bytes::from(header)).await.expect("queue header");
    drop(post);
    let mut authenticated = false;
    let session = run_session(
        inbound,
        outbound,
        IDENTITY,
        None,
        |ip| ip.is_loopback(),
        || {
            authenticated = true;
            true
        },
    );
    let (result, accepted) = tokio::join!(
        timeout(Duration::from_millis(250), session),
        timeout(Duration::from_millis(100), listener.accept()),
    );
    assert!(accepted.is_err(), "rejected header must not open a target connection");
    let error = result.expect("rejection must finish promptly").expect_err("unsupported request");
    assert_eq!(error.kind(), kind);
    assert!(!authenticated, "rejected header must not authenticate");
    assert!(download.recv().await.is_none(), "rejected header must not send a success response");
    if kind == io::ErrorKind::Unsupported {
        assert_eq!(error.to_string(), "Cloudflare origin supports only empty VLESS flow");
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops both joined futures and their loopback resources.
#[tokio::test(flavor = "current_thread")]
async fn empty_flow_with_unknown_addons_preserves_bidirectional_payload() {
    for addons in [b"".as_slice(), b"\x0a\x00\x18\x01".as_slice()] {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind target");
        let target = listener.local_addr().expect("target address").to_string();
        let (post, inbound) = mpsc::channel(1);
        let (outbound, mut download) = mpsc::channel(2);
        let mut header = ripdpi_vless::wire::encode_request(&IDENTITY, addons, &target).expect("VLESS request");
        header.extend_from_slice(b"request");
        post.send(Bytes::from(header)).await.expect("queue request");
        drop(post);
        let mut authenticated = false;
        let session = run_session(
            inbound,
            outbound,
            IDENTITY,
            None,
            |ip| ip.is_loopback(),
            || {
                authenticated = true;
                true
            },
        );
        let exchange = async {
            let (mut target, _) = listener.accept().await.expect("accept target connection");
            let mut received = [0; 7];
            target.read_exact(&mut received).await.expect("target payload");
            assert_eq!(&received, b"request");
            assert_eq!(download.recv().await.expect("response header").expect("success").as_ref(), b"\0\0");
            target.write_all(b"response").await.expect("target response");
            target.shutdown().await.expect("target close");
            let mut response = Vec::new();
            while let Some(chunk) = download.recv().await {
                response.extend_from_slice(&chunk.expect("response chunk"));
            }
            assert_eq!(response, b"response");
        };
        let (result, ()) = timeout(Duration::from_secs(1), async { tokio::join!(session, exchange) })
            .await
            .expect("exchange deadline");
        result.expect("plain TCP relay");
        assert!(authenticated);
    }
}
