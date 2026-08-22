//! Per-transport throughput benchmarks.
//!
//! Each benchmark drives a real protocol *client* against its in-process
//! loopback *server* fixture and measures the steady-state throughput of a
//! 1 MiB application-data round-trip through the fully framed tunnel (TLS /
//! HTTP/2 / HMAC framing included). The connection + handshake is established
//! once per benchmark, outside the timed loop, so the measurement reflects data
//! throughput rather than handshake cost.
//!
//! Coverage: all 7 transports — VLESS+Reality, VLESS-over-xHTTP-over-Reality,
//! ShadowTLS v3, MASQUE (H2 CONNECT-TCP), WS-tunnel (WebTunnel
//! HTTP-Upgrade-over-TLS), Hysteria 2, and TUIC v5 — each driven by the real
//! client against an in-process protocol-server loopback (see
//! `docs/architecture/protocol-loopback-harness-design.md`).
//!
//! Notes on the trickier transports: MASQUE and TUIC pin the fixture's
//! self-signed cert via their `root_certificate_pem` config option (TLS
//! verification stays ON) rather than relaxing verification; WS-tunnel uses the
//! async `connect_webtunnel_async` client; Hysteria 2 drives the real client
//! against `Hysteria2Loopback` (quinn + h3 auth + raw proxy streams) with the
//! client's runtime `insecure` flag, after a client fix to keep its h3
//! `SendRequest` alive so the post-auth h3 shutdown no longer closes the shared
//! QUIC connection.
//!
//! Baselines are intentionally NOT committed from a developer machine —
//! Criterion numbers are host-dependent and a dev-box baseline would gate CI on
//! noise. The `regression-detector` baseline must be captured on the CI
//! reference runner; see the crate README.

use std::time::Duration;

use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::runtime::Runtime;

use local_network_fixture::{
    Hysteria2Loopback, MasqueH2ConnectUdpFixture, TuicLoopback, VlessRealityLoopback, WebTunnelFixture,
    XhttpRealityLoopback,
};
use ripdpi_masque::MasqueClient;
use ripdpi_masque::config::MasqueConfig;
use ripdpi_shadowtls::{Config as ShadowTlsConfig, ShadowTlsClient, ShadowTlsLoopback};
use ripdpi_vless::VlessRealityClient;
use ripdpi_vless::config::VlessRealityConfig;
use ripdpi_webtunnel::bridge_line::parse_bridge_line;
use ripdpi_webtunnel::client::connect_webtunnel_async;
use ripdpi_xhttp::{XhttpRealityConfig, connect_reality};

const PAYLOAD_LEN: usize = 1024 * 1024;
/// base64 of 32 bytes — a syntactically valid REALITY public key. The loopback
/// servers do not validate it (REALITY auth is disabled in loopback mode).
const REALITY_PUBLIC_KEY_B64: &str = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
const UUID: &str = "11111111-1111-1111-1111-111111111111";

fn runtime() -> Runtime {
    tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build bench runtime")
}

/// One full-duplex 1 MiB round-trip: write the payload while concurrently
/// reading its echo back. The concurrency is load-bearing — writing the whole
/// payload before reading would deadlock once it exceeds the tunnel's buffers
/// (the echo back-pressures the writer).
async fn roundtrip<R, W>(reader: &mut R, writer: &mut W, payload: &[u8], recv: &mut [u8])
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let write = async {
        writer.write_all(payload).await.expect("write payload");
        writer.flush().await.expect("flush payload");
    };
    let read = async {
        reader.read_exact(recv).await.expect("read echo");
    };
    tokio::join!(write, read);
}

fn vless_reality_config(server_port: u16, server_name: &str) -> VlessRealityConfig {
    VlessRealityConfig::from_strings(
        "127.0.0.1",
        i32::from(server_port),
        UUID,
        server_name,
        REALITY_PUBLIC_KEY_B64,
        "",
        "chrome_stable",
    )
    .expect("valid VLESS Reality config")
}

fn bench_vless_reality(c: &mut Criterion, rt: &Runtime) {
    let server = rt.block_on(VlessRealityLoopback::start()).expect("start VLESS fixture");
    let target = format!("127.0.0.1:{}", server.target_port());
    let config = vless_reality_config(server.port(), server.server_name());

    let stream = rt.block_on(VlessRealityClient::connect(&config, &target)).expect("VLESS connect");
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("vless_reality/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_vless_over_xhttp_reality(c: &mut Criterion, rt: &Runtime) {
    let server = rt.block_on(XhttpRealityLoopback::start()).expect("start xHTTP fixture");
    let target = format!("127.0.0.1:{}", server.target_port());
    let config = XhttpRealityConfig {
        // Vision flow is invalid over xHTTP; the production builder clears it and
        // the loopback fixture now rejects any non-empty addons block, so the
        // bench must mirror that to connect.
        vless: vless_reality_config(server.port(), server.server_name())
            .with_flow(ripdpi_vless::addons::VlessFlow::None),
        path: "/tunnel".to_string(),
        host: None,
        bind_ip: None,
        socket_protector: None,
        xmux: ripdpi_xhttp::XmuxConfig::default(),
        finalmask: ripdpi_xhttp::FinalmaskConfig::default(),
        protocol_mode: ripdpi_xhttp::XhttpProtocolMode::default(),
    };

    let stream = rt.block_on(connect_reality(&config, &target)).expect("xHTTP connect");
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("vless_over_xhttp_reality/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_shadowtls(c: &mut Criterion, rt: &Runtime) {
    const PASSWORD: &str = "bench-shadowtls-password";
    let server = rt.block_on(ShadowTlsLoopback::start(PASSWORD.to_string())).expect("start ShadowTLS fixture");
    let local_addr = server.local_addr();

    // The ShadowTLS loopback HMAC-echoes application data directly (no separate
    // upstream), so the tunnel stream itself is the echo round-trip.
    let stream = rt.block_on(async {
        let tcp = tokio::net::TcpStream::connect(local_addr).await.expect("tcp connect");
        tcp.set_nodelay(true).expect("set nodelay");
        let client = ShadowTlsClient::new(ShadowTlsConfig {
            password: PASSWORD.to_string(),
            server_name: "localhost".to_string(),
            inner_profile_id: "default".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
            outbound_bind_ip: None,
        });
        client.connect_over(tcp).await.expect("ShadowTLS handshake")
    });
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("shadowtls_v3/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_masque_h2_connect_tcp(c: &mut Criterion, rt: &Runtime) {
    let server = rt.block_on(MasqueH2ConnectUdpFixture::start()).expect("start MASQUE fixture");
    let masque_url = server.masque_url();
    // masque_url is https://127.0.0.1:PORT/.well-known/masque/ip — derive the
    // proxy SocketAddr so we can pre-connect a raw transport and use
    // connect_over (avoids MasqueClient::connect's ~5s H3-first probe; the
    // fixture is H2-only).
    let proxy_addr: std::net::SocketAddr = {
        let port = masque_url
            .rsplit(':')
            .next()
            .and_then(|tail| tail.split('/').next())
            .and_then(|port| port.parse::<u16>().ok())
            .expect("MASQUE fixture url carries a port");
        (std::net::Ipv4Addr::LOCALHOST, port).into()
    };
    let target = server.tcp_echo_target();
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
        url: masque_url,
        proxy_socket_addr: None,
        tcp_protocol: ripdpi_masque::config::MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        // Pin the fixture's self-signed cert; TLS verification stays ON.
        root_certificate_pem: Some(server.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };

    let stream = rt.block_on(async {
        let transport = tokio::net::TcpStream::connect(proxy_addr).await.expect("connect MASQUE proxy transport");
        MasqueClient::connect_over(&config, transport, &target).await.expect("MASQUE connect_over")
    });
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("masque_h2_connect_tcp/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_ws_tunnel(c: &mut Criterion, rt: &Runtime) {
    // WebTunnelFixture::start is synchronous (std threads); the async client
    // connects to it over TCP. The fixture echoes application bytes on the
    // tunnel itself (no separate upstream).
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url={} addr={} servername=localhost utls=hellochrome_auto",
        fixture.url(),
        fixture.addr(),
    ))
    .expect("WebTunnel bridge line");

    // verify=false: the fixture's self-signed cert is not exposed; this is a
    // loopback test fixture, not a production path (matches the crate's own e2e).
    let stream = rt.block_on(connect_webtunnel_async(&bridge, false)).expect("WebTunnel async connect");
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("ws_tunnel/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_hysteria2(c: &mut Criterion, rt: &Runtime) {
    let server = rt.block_on(Hysteria2Loopback::start()).expect("start Hysteria2 fixture");
    // insecure=1 makes the client trust the fixture's self-signed cert via its
    // runtime flag (no cfg(test) gating); the loopback echoes regardless of the
    // requested target, so any well-formed address works.
    let url = format!("hysteria2://bench@127.0.0.1:{}/?sni=localhost&insecure=1", server.port());
    let config = ripdpi_hysteria2::Config::from_url(&url).expect("hysteria2 config");

    let stream = rt.block_on(async {
        let client = ripdpi_hysteria2::connect(&config).await.expect("hysteria2 connect");
        client.tcp_connect("127.0.0.1:9").await.expect("hysteria2 tcp_connect")
    });
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("hysteria2/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_tuic(c: &mut Criterion, rt: &Runtime) {
    let server = rt.block_on(TuicLoopback::start()).expect("start TUIC fixture");
    let config = ripdpi_tuic::Config {
        server: "127.0.0.1".to_string(),
        server_port: i32::from(server.port()),
        server_name: "localhost".to_string(),
        uuid: "11111111-1111-1111-1111-111111111111".to_string(),
        password: "bench-tuic-password".to_string(),
        zero_rtt: false,
        congestion_control: "bbr".to_string(),
        udp_enabled: false,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
        outbound_bind_ip: None,
        keepalive_interval_ms: 0,
        // Pin the fixture's self-signed cert; TLS verification stays ON.
        root_certificate_pem: Some(server.certificate_pem().to_string()),
    };

    let stream = rt.block_on(async {
        let client = ripdpi_tuic::TuicClient::connect(config).await.expect("tuic connect");
        client.tcp_connect("127.0.0.1:9").await.expect("tuic tcp_connect")
    });
    let (mut reader, mut writer) = tokio::io::split(stream);

    let payload = vec![0xAB_u8; PAYLOAD_LEN];
    let mut recv = vec![0_u8; PAYLOAD_LEN];

    let mut group = c.benchmark_group("protocol-throughput");
    group.throughput(Throughput::Bytes(PAYLOAD_LEN as u64));
    group.bench_function("tuic/1MiB", |b| {
        b.iter(|| rt.block_on(roundtrip(&mut reader, &mut writer, &payload, &mut recv)));
    });
    group.finish();

    drop((reader, writer));
}

fn bench_protocol_throughput(c: &mut Criterion) {
    let rt = runtime();
    bench_vless_reality(c, &rt);
    bench_vless_over_xhttp_reality(c, &rt);
    bench_shadowtls(c, &rt);
    bench_masque_h2_connect_tcp(c, &rt);
    bench_ws_tunnel(c, &rt);
    bench_hysteria2(c, &rt);
    bench_tuic(c, &rt);
}

criterion_group! {
    name = protocol_throughput;
    config = Criterion::default()
        .sample_size(20)
        .measurement_time(Duration::from_secs(10));
    targets = bench_protocol_throughput
}

criterion_main!(protocol_throughput);
