use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_config::RuntimeConfig;

fn recv_exact(stream: &mut TcpStream, size: usize) -> Vec<u8> {
    let mut buf = vec![0u8; size];
    stream.read_exact(&mut buf).expect("recv_exact");
    buf
}

fn socks5_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set read timeout");
    stream.set_write_timeout(Some(Duration::from_secs(5))).expect("set write timeout");

    stream.write_all(b"\x05\x01\x00").expect("write socks auth");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x00");

    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks connect");

    let header = recv_exact(&mut stream, 4);
    match header[3] {
        0x01 => {
            let _ = recv_exact(&mut stream, 6);
        }
        0x04 => {
            let _ = recv_exact(&mut stream, 18);
        }
        atyp => panic!("unsupported SOCKS5 reply ATYP: {atyp}"),
    }
    assert_eq!(header[1], 0x00, "SOCKS5 CONNECT failed: {header:?}");

    stream
}

struct BenchInfra {
    _fixture: FixtureStack,
    proxy_port: u16,
    echo_port: u16,
    control: Arc<ripdpi_runtime::EmbeddedProxyControl>,
    proxy_thread: Option<JoinHandle<std::io::Result<()>>>,
}

impl BenchInfra {
    fn start() -> Self {
        let fixture = FixtureStack::start(FixtureConfig::default()).expect("start fixture stack");
        let echo_port = fixture.manifest().tcp_echo_port;

        ripdpi_runtime::process::prepare_embedded();

        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;

        let listener = ripdpi_runtime::runtime::create_listener(&config).expect("create proxy listener");
        let proxy_port = listener.local_addr().expect("proxy local addr").port();

        let control = Arc::new(ripdpi_runtime::EmbeddedProxyControl::new(None));
        let control_clone = control.clone();
        let proxy_thread = thread::spawn(move || {
            ripdpi_runtime::runtime::run_proxy_with_embedded_control(config, listener, control_clone)
        });

        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            match TcpStream::connect_timeout(&(Ipv4Addr::LOCALHOST, proxy_port).into(), Duration::from_millis(100)) {
                Ok(probe) => {
                    drop(probe);
                    break;
                }
                Err(_) if std::time::Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(50));
                }
                Err(err) => panic!("proxy did not start within 2s: {err}"),
            }
        }

        Self { _fixture: fixture, proxy_port, echo_port, control, proxy_thread: Some(proxy_thread) }
    }
}

impl Drop for BenchInfra {
    fn drop(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect_timeout(&(Ipv4Addr::LOCALHOST, self.proxy_port).into(), Duration::from_millis(500));
        if let Some(handle) = self.proxy_thread.take() {
            let _ = handle.join();
        }
        ripdpi_runtime::clear_runtime_telemetry();
    }
}

fn bench_relay_connect_setup(c: &mut Criterion) {
    let infra = BenchInfra::start();
    let mut group = c.benchmark_group("relay/connect-setup");
    group.throughput(Throughput::Elements(1));

    group.bench_with_input(BenchmarkId::new("socks5-connect", "tcp-echo"), &infra.echo_port, |b, &echo_port| {
        b.iter(|| {
            let stream = socks5_connect(infra.proxy_port, echo_port);
            std::hint::black_box(stream);
        });
    });

    group.finish();
    drop(infra);
}

criterion_group! {
    name = relay_connect_setup;
    config = Criterion::default()
        .sample_size(30)
        .measurement_time(Duration::from_secs(12));
    targets = bench_relay_connect_setup
}

criterion_main!(relay_connect_setup);
