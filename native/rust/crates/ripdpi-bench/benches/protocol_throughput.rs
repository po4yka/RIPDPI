//! Per-transport throughput benchmarks.
//!
//! Each benchmark drives a real protocol *client* against its in-process
//! loopback *server* fixture and measures the steady-state throughput of a
//! 1 MiB application-data round-trip through the fully framed tunnel (TLS /
//! HTTP/2 / HMAC framing included). The connection + handshake is established
//! once per benchmark, outside the timed loop, so the measurement reflects data
//! throughput rather than handshake cost.
//!
//! Coverage today: VLESS+Reality and VLESS-over-xHTTP-over-Reality — the
//! transports with a drivable protocol-server loopback that pipeline cleanly
//! (see `docs/architecture/protocol-loopback-harness-design.md`). Deferred, with
//! what each needs first:
//!   - ShadowTLS v3: `ShadowTlsStream` shares `pending_frame`/`pending_frame_offset`
//!     between `poll_read` and `poll_write`, which serializes bidirectional
//!     traffic into a frame-by-frame ping-pong. Measured here at ~0.5 MiB/s under
//!     concurrent split read+write (vs ~70 MiB/s for VLESS) because the
//!     serialized request/response pattern hits the ~40ms delayed-ACK timer per
//!     16 KiB frame. The relay's `copy_bidirectional` would throttle the same
//!     way, so this needs investigation before a meaningful baseline — see
//!     `docs/tasks/issues/investigate-shadowtls-stream-concurrent-throughput.md`.
//!   - Hysteria 2 / TUIC: a QUIC *proxy-server* loopback (the existing
//!     `QuicLoopback` is a generic echo, not a Hysteria2/TUIC protocol server).
//!   - MASQUE / WS-tunnel: drive `local-network-fixture`'s `masque` / `webtunnel`
//!     fixtures through their clients (auth/bootstrap plumbing pending).
//!
//! Baselines are intentionally NOT committed from a developer machine —
//! Criterion numbers are host-dependent and a dev-box baseline would gate CI on
//! noise. The `regression-detector` baseline must be captured on the CI
//! reference runner; see the crate README.

use std::time::Duration;

use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::runtime::Runtime;

use local_network_fixture::{VlessRealityLoopback, XhttpRealityLoopback};
use ripdpi_vless::VlessRealityClient;
use ripdpi_vless::config::VlessRealityConfig;
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
        vless: vless_reality_config(server.port(), server.server_name()),
        path: "/tunnel".to_string(),
        host: None,
        bind_ip: None,
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

fn bench_protocol_throughput(c: &mut Criterion) {
    let rt = runtime();
    bench_vless_reality(c, &rt);
    bench_vless_over_xhttp_reality(c, &rt);
}

criterion_group! {
    name = protocol_throughput;
    config = Criterion::default()
        .sample_size(20)
        .measurement_time(Duration::from_secs(10));
    targets = bench_protocol_throughput
}

criterion_main!(protocol_throughput);
