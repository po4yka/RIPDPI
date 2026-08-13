use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use android_support::EventRingLayer;
use local_network_fixture::VlessRealityLoopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tracing::{Instrument as _, instrument::WithSubscriber as _};
use tracing_subscriber::prelude::*;

use super::{RelayTargetAddr, build_backend, sample_config, valid_reality_public_key, vless_config_mut};

#[tokio::test]
async fn vless_reality_success_exports_observed_handshake_and_first_response_stages() {
    let fixture = VlessRealityLoopback::start().await.expect("start VLESS Reality fixture");
    let mut config = sample_config("vless_reality");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.reality_public_key = valid_reality_public_key();
    vless.vless_flow = "none".to_string();
    vless.vless_mux_protocol.clear();
    let backend = build_backend(&config).await.expect("build VLESS Reality backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));

    let buffers = android_support::EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let dispatch = tracing::Dispatch::new(subscriber);
    let attempt = tracing::dispatcher::with_default(&dispatch, || {
        tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            source = "ripdpi-relay-core",
            subsystem = "relay",
            runtime_id = "runtime-reality-success",
            attempt_id = 6_u64,
        )
    });
    async {
        let mut stream = backend.connect_tcp(&target).await.expect("open direct VLESS Reality stream");
        stream.write_all(b"ping").await.expect("write echo payload");
        let mut reply = [0_u8; 4];
        stream.read_exact(&mut reply).await.expect("read echo payload and VLESS response");
        assert_eq!(&reply, b"ping");
    }
    .instrument(attempt)
    .with_subscriber(dispatch)
    .await;

    let stages = buffers
        .drain_relay_for_runtime("runtime-reality-success")
        .events
        .into_iter()
        .filter_map(|event| event.stage.zip(event.outcome))
        .collect::<Vec<_>>();
    assert_eq!(
        stages,
        [
            ("tcp_connect".to_string(), "started".to_string()),
            ("tcp_connect".to_string(), "succeeded".to_string()),
            ("reality_tls".to_string(), "started".to_string()),
            ("reality_tls".to_string(), "succeeded".to_string()),
            ("vless_request".to_string(), "started".to_string()),
            ("vless_request".to_string(), "succeeded".to_string()),
            ("vless_response".to_string(), "started".to_string()),
            ("vless_response".to_string(), "succeeded".to_string()),
        ],
    );
}

#[tokio::test]
async fn vless_reality_handshake_close_exports_only_observed_stage_prefix() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind closing Reality peer");
    let relay_address = listener.local_addr().expect("closing Reality peer address");
    let peer = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.expect("accept VLESS Reality client");
        drop(stream);
    });

    let mut config = sample_config("vless_reality");
    config.common.server = relay_address.ip().to_string();
    config.common.server_port = i32::from(relay_address.port());
    config.common.server_name = "relay.test".to_string();
    vless_config_mut(&mut config).reality_public_key = valid_reality_public_key();
    let backend = build_backend(&config).await.expect("build VLESS Reality backend");
    let target = RelayTargetAddr::Domain("target.test".to_string(), 443);

    let buffers = android_support::EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let dispatch = tracing::Dispatch::new(subscriber);
    let attempt = tracing::dispatcher::with_default(&dispatch, || {
        tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            source = "ripdpi-relay-core",
            subsystem = "relay",
            runtime_id = "runtime-reality-close",
            attempt_id = 7_u64,
        )
    });

    let Err(error) = backend.connect_tcp(&target).instrument(attempt).with_subscriber(dispatch).await else {
        panic!("closing peer must fail the Reality handshake");
    };
    peer.await.expect("closing Reality peer task");
    assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);

    let events = buffers.drain_relay_for_runtime("runtime-reality-close").events;
    assert!(events.iter().all(|event| event.attempt_id == Some(7)), "one attempt must own the complete trace");
    assert_eq!(
        events
            .iter()
            .map(|event| {
                (
                    event.attempt_sequence,
                    event.stage.as_deref(),
                    event.outcome.as_deref(),
                    event.failure_stage.as_deref(),
                )
            })
            .collect::<Vec<_>>(),
        [
            (Some(1), Some("tcp_connect"), Some("started"), None),
            (Some(2), Some("tcp_connect"), Some("succeeded"), None),
            (Some(3), Some("reality_tls"), Some("started"), None),
            (Some(4), Some("reality_tls"), Some("failed"), Some("reality_tls")),
        ],
    );
    let failure = events.last().expect("Reality failure event");
    assert_eq!(failure.io_error_kind.as_deref(), Some("connection_refused"));
    assert_eq!(failure.os_error_code, error.raw_os_error());
    assert!(
        events.iter().all(|event| {
            !matches!(event.stage.as_deref(), Some("vless_request" | "vless_response" | "socks_reply"))
        }),
        "the trace must not fabricate stages after the observed Reality failure",
    );
}

#[tokio::test]
async fn vless_reality_mux_reuse_marks_carrier_without_replaying_handshake_stages() {
    let fixture = VlessRealityLoopback::start().await.expect("start VLESS Reality mux fixture");
    let mut config = sample_config("vless_reality");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.reality_public_key = valid_reality_public_key();
    vless.vless_flow = "none".to_string();
    vless.vless_mux_protocol = "yamux".to_string();
    vless.vless_mux_max_concurrent_streams = 2;

    let backend = build_backend(&config).await.expect("build VLESS Reality mux backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    drop(backend.connect_tcp(&target).await.expect("establish reusable mux carrier"));
    assert_eq!(backend.pool_health().map(|health| health.idle_streams), Some(1));

    let buffers = android_support::EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let dispatch = tracing::Dispatch::new(subscriber);
    let attempt = tracing::dispatcher::with_default(&dispatch, || {
        tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            source = "ripdpi-relay-core",
            subsystem = "relay",
            runtime_id = "runtime-mux-reuse",
            attempt_id = 8_u64,
        )
    });

    drop(
        backend
            .connect_tcp(&target)
            .instrument(attempt)
            .with_subscriber(dispatch)
            .await
            .expect("open stream on cached mux carrier"),
    );

    let events = buffers.drain_relay_for_runtime("runtime-mux-reuse").events;
    let reused = events.iter().filter(|event| event.carrier_disposition.as_deref() == Some("carrier_reused"));
    assert_eq!(reused.count(), 1, "the logical attempt must explicitly record reuse of the existing carrier");
    assert!(
        events.iter().all(|event| !matches!(event.stage.as_deref(), Some("tcp_connect" | "reality_tls"))),
        "a reused carrier must not fabricate TCP connect or Reality TLS stages for the new logical attempt",
    );
}
