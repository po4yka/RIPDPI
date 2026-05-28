use std::env;
use std::ffi::OsStr;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_tor::{load_arti_config_from_toml, TorRelayClient, TorTarget};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::{timeout, Duration};

const CHUTNEY_VERIFY_PORT: u16 = 4747;

#[tokio::test(flavor = "multi_thread")]
async fn chutney_bridge_network_connects_tcp_stream() {
    if env::var_os("RIPDPI_TOR_CHUTNEY_E2E").is_none() {
        eprintln!("set RIPDPI_TOR_CHUTNEY_E2E=1 to run the local Chutney Tor network E2E");
        return;
    }

    let network = ChutneyNetwork::bootstrap("bridges-min");
    let config = load_arti_config_from_toml(network.arti_config_path()).expect("load chutney arti config");
    let client = timeout(Duration::from_secs(90), TorRelayClient::create_bootstrapped(config))
        .await
        .expect("arti client bootstrap timed out")
        .expect("bootstrap arti client");

    let listener = TcpListener::bind(("127.0.0.1", CHUTNEY_VERIFY_PORT)).await.expect("bind target listener");
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.expect("accept Tor exit stream");
        let mut request = [0_u8; 4];
        socket.read_exact(&mut request).await.expect("read request");
        assert_eq!(&request, b"ping");
        socket.write_all(b"pong").await.expect("write response");
    });

    let target = TorTarget::new("127.0.0.1", CHUTNEY_VERIFY_PORT).expect("local target accepted by chutney config");
    let mut stream = timeout(Duration::from_secs(30), client.connect_tcp(&target))
        .await
        .expect("Tor TCP connect timed out")
        .expect("connect through Arti");
    timeout(Duration::from_secs(10), stream.write_all(b"ping"))
        .await
        .expect("write through Tor stream timed out")
        .expect("write through Tor stream");
    timeout(Duration::from_secs(10), stream.flush())
        .await
        .expect("flush through Tor stream timed out")
        .expect("flush through Tor stream");
    let mut response = [0_u8; 4];
    timeout(Duration::from_secs(10), stream.read_exact(&mut response))
        .await
        .expect("read through Tor stream timed out")
        .expect("read through Tor stream");
    assert_eq!(&response, b"pong");

    server.await.expect("target server task");
}

struct ChutneyNetwork {
    data_dir: PathBuf,
}

impl ChutneyNetwork {
    fn bootstrap(network_name: &str) -> Self {
        let data_dir = unique_data_dir(network_name);
        let _ = fs::remove_dir_all(&data_dir);
        fs::create_dir_all(&data_dir).expect("create chutney data dir");
        let network = Self { data_dir };
        run_chutney(&network.data_dir, ["init", "--net", network_name]);
        run_chutney(&network.data_dir, ["bootstrap"]);
        network
    }

    fn arti_config_path(&self) -> PathBuf {
        self.data_dir.join("nodes").join("arti.toml")
    }
}

impl Drop for ChutneyNetwork {
    fn drop(&mut self) {
        let _ = Command::new("chutney").arg("--data-dir").arg(&self.data_dir).arg("stop").output();
        let _ = fs::remove_dir_all(&self.data_dir);
    }
}

fn unique_data_dir(network_name: &str) -> PathBuf {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).expect("system clock after epoch").as_millis();
    PathBuf::from("/tmp").join(format!("ripdpi-tor-{network_name}-{}-{now}", std::process::id()))
}

fn run_chutney<I, S>(data_dir: &Path, args: I)
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let output = Command::new("chutney").arg("--data-dir").arg(data_dir).args(args).output().expect("run chutney");
    assert_success(output);
}

fn assert_success(output: Output) {
    if output.status.success() {
        return;
    }
    panic!(
        "chutney failed with status {}\nstdout:\n{}\nstderr:\n{}",
        output.status,
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}
