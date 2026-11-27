#![cfg(not(feature = "loom"))]

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

const TOKEN: &str = "session-token";
const TIMEOUT: Duration = Duration::from_secs(5);

/// Echoes every byte back and counts accepted connections.
struct Upstream {
    addr: SocketAddr,
    accepted: Arc<AtomicUsize>,
}

impl Upstream {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
        let addr = listener.local_addr().expect("upstream address");
        let accepted = Arc::new(AtomicUsize::new(0));
        let counter = accepted.clone();
        thread::spawn(move || {
            while let Ok((mut stream, _)) = listener.accept() {
                counter.fetch_add(1, Ordering::SeqCst);
                thread::spawn(move || {
                    let mut buf = [0_u8; 64];
                    while let Ok(n) = stream.read(&mut buf) {
                        if n == 0 || stream.write_all(&buf[..n]).is_err() {
                            break;
                        }
                    }
                });
            }
        });
        Self { addr, accepted }
    }

    fn assert_untouched(&self) {
        thread::sleep(Duration::from_millis(100));
        assert_eq!(self.accepted.load(Ordering::SeqCst), 0, "a refused client must not open an upstream connection");
    }
}

fn unix_config(path: &Path, token: Option<&str>) -> RuntimeConfig {
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = Ipv4Addr::LOCALHOST.into();
    config.network.listen.listen_port = 0;
    config.network.listen.unix_path = Some(path.to_owned());
    config.network.listen.auth_token = token.map(str::to_owned);
    config.network.ipv6 = false;
    config.network.mixed = true;
    config
}

struct Runtime {
    control: Arc<EmbeddedProxyControl>,
    done: mpsc::Receiver<io::Result<ripdpi_proxy_runtime::ProxyRuntimeCleanupReceipt>>,
}

impl Runtime {
    fn start(config: RuntimeConfig) -> Self {
        prepare_embedded();
        let listener = create_listener(&config).expect("unix proxy listener");
        let control = Arc::new(EmbeddedProxyControl::new(None));
        let worker_control = control.clone();
        let (done_tx, done) = mpsc::channel();
        thread::spawn(move || {
            let _ = done_tx.send(run_proxy_with_embedded_control_receipt(
                config,
                listener,
                worker_control,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            ));
        });
        Self { control, done }
    }

    fn stop(self) {
        self.control.request_shutdown();
        let receipt = self.done.recv_timeout(TIMEOUT).expect("runtime stops").expect("runtime result");
        assert!(!receipt.worker_panicked());
    }
}

fn client(path: &Path) -> UnixStream {
    let stream = UnixStream::connect(path).expect("connect to the unix listener");
    stream.set_read_timeout(Some(TIMEOUT)).expect("client read timeout");
    stream
}

fn socks5_authenticate(stream: &mut UnixStream) {
    stream.write_all(&[5, 1, 2]).expect("offer username/password");
    let mut reply = [0_u8; 2];
    stream.read_exact(&mut reply).expect("method reply");
    assert_eq!(reply, [5, 2]);
    let mut frame = vec![1, 1, b'u', TOKEN.len() as u8];
    frame.extend_from_slice(TOKEN.as_bytes());
    stream.write_all(&frame).expect("send credentials");
    stream.read_exact(&mut reply).expect("auth reply");
    assert_eq!(reply, [1, 0]);
}

fn socks5_connect(stream: &mut UnixStream, target: SocketAddr) {
    let mut request = vec![5, 1, 0, 1, 127, 0, 0, 1];
    request.extend_from_slice(&target.port().to_be_bytes());
    stream.write_all(&request).expect("send CONNECT");
    let mut reply = [0_u8; 10];
    stream.read_exact(&mut reply).expect("CONNECT reply");
    assert_eq!(&reply[..2], &[5, 0], "CONNECT must succeed");
}

fn socket_dir() -> tempfile::TempDir {
    tempfile::tempdir().expect("socket directory")
}

#[test]
fn unix_listener_relays_an_authenticated_socks5_connect() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let upstream = Upstream::start();
    let runtime = Runtime::start(unix_config(&path, Some(TOKEN)));

    let mut stream = client(&path);
    socks5_authenticate(&mut stream);
    socks5_connect(&mut stream, upstream.addr);
    stream.write_all(b"ping").expect("send payload");
    let mut echoed = [0_u8; 4];
    stream.read_exact(&mut echoed).expect("echoed payload");
    assert_eq!(&echoed, b"ping");

    runtime.stop();
}

#[test]
fn unix_listener_binds_no_tcp_socket_and_owns_a_private_file() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let reserved = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("reserve a port");
    let port = reserved.local_addr().expect("reserved address").port();
    drop(reserved);
    let mut config = unix_config(&path, Some(TOKEN));
    config.network.listen.listen_port = port;
    let runtime = Runtime::start(config);

    assert_eq!(std::fs::metadata(&path).expect("socket file").permissions().mode() & 0o777, 0o600);
    TcpListener::bind((Ipv4Addr::LOCALHOST, port)).expect("the configured TCP port stays free");

    runtime.stop();
    assert!(!path.exists(), "stopping the runtime removes the socket file");
}

#[test]
fn unix_listener_refuses_to_replace_an_existing_file() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    std::fs::write(&path, b"keep").expect("pre-existing file");

    let err = create_listener(&unix_config(&path, Some(TOKEN))).expect_err("bind must fail");

    assert_eq!(err.kind(), io::ErrorKind::AddrInUse);
    assert_eq!(std::fs::read(&path).expect("file survives"), b"keep");
}

#[test]
fn unix_listener_requires_a_token() {
    let dir = socket_dir();
    let path: PathBuf = dir.path().join("px-test");

    let err = create_listener(&unix_config(&path, None)).expect_err("a token is required");

    assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);
    assert!(!path.exists(), "validation fails before the bind");
}

#[test]
fn unix_listener_rejects_transparent_mode() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let mut config = unix_config(&path, Some(TOKEN));
    config.network.transparent = true;

    let err = create_listener(&config).expect_err("transparent mode cannot authenticate");

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    assert!(!path.exists());
}

#[test]
fn unix_listener_refuses_anonymous_socks5() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let upstream = Upstream::start();
    let runtime = Runtime::start(unix_config(&path, Some(TOKEN)));

    let mut stream = client(&path);
    stream.write_all(&[5, 1, 0]).expect("offer no authentication");
    let mut reply = [0_u8; 2];
    stream.read_exact(&mut reply).expect("method reply");
    assert_eq!(reply, [5, 0xff]);
    let mut rest = Vec::new();
    stream.read_to_end(&mut rest).expect("connection closes");
    assert!(rest.is_empty());

    upstream.assert_untouched();
    runtime.stop();
}

#[test]
fn unix_listener_refuses_socks4_connect() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let upstream = Upstream::start();
    let runtime = Runtime::start(unix_config(&path, Some(TOKEN)));

    let mut stream = client(&path);
    let mut request = vec![4, 1];
    request.extend_from_slice(&upstream.addr.port().to_be_bytes());
    request.extend_from_slice(&[127, 0, 0, 1, 0]);
    stream.write_all(&request).expect("send SOCKS4 CONNECT");
    let mut reply = [0_u8; 8];
    stream.read_exact(&mut reply).expect("SOCKS4 reply");
    assert_eq!(&reply[..2], &[0, 0x5b], "request rejected");

    upstream.assert_untouched();
    runtime.stop();
}

#[test]
fn unix_listener_refuses_unauthenticated_http_connect() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let upstream = Upstream::start();
    let runtime = Runtime::start(unix_config(&path, Some(TOKEN)));

    let mut stream = client(&path);
    let target = upstream.addr;
    write!(stream, "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n").expect("send HTTP CONNECT");
    let mut reply = Vec::new();
    stream.read_to_end(&mut reply).expect("reply and close");
    assert!(reply.starts_with(b"HTTP/1.1 407"), "got {:?}", String::from_utf8_lossy(&reply));

    upstream.assert_untouched();
    runtime.stop();
}

#[test]
fn runtime_stop_shuts_down_an_idle_unix_client() {
    let dir = socket_dir();
    let path = dir.path().join("px-test");
    let upstream = Upstream::start();
    let runtime = Runtime::start(unix_config(&path, Some(TOKEN)));
    let mut stream = client(&path);
    socks5_authenticate(&mut stream);
    socks5_connect(&mut stream, upstream.addr);

    runtime.stop();

    let mut byte = [0_u8; 1];
    match stream.read(&mut byte) {
        Ok(0) => {}
        Ok(n) => panic!("unexpected {n} bytes after stop"),
        Err(err) => assert!(
            !matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut),
            "the idle client must be closed, not left to time out: {err}"
        ),
    }
}
