use std::env;
use std::ffi::OsStr;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_tor::{TorRelayClient, TorTarget, load_arti_config_from_toml, prepare_arti_state_dirs};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::{Duration, timeout};

const CHUTNEY_VERIFY_PORT: u16 = 4747;

#[tokio::test(flavor = "multi_thread")]
async fn chutney_network_connects_tcp_stream() {
    if env::var_os("RIPDPI_TOR_CHUTNEY_E2E").is_none() {
        eprintln!("set RIPDPI_TOR_CHUTNEY_E2E=1 to run the local Chutney Tor network E2E");
        return;
    }

    let network = ChutneyNetwork::bootstrap("basic-min");
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

#[tokio::test(flavor = "multi_thread")]
async fn chutney_bridge_network_bootstraps_arti_client() {
    if env::var_os("RIPDPI_TOR_CHUTNEY_E2E").is_none() {
        eprintln!("set RIPDPI_TOR_CHUTNEY_E2E=1 to run the local Chutney Tor network E2E");
        return;
    }

    let network = ChutneyNetwork::bootstrap("bridges-min");
    let config = load_arti_config_from_toml(network.arti_config_path()).expect("load chutney bridge arti config");
    timeout(Duration::from_secs(90), TorRelayClient::create_bootstrapped(config))
        .await
        .expect("bridge arti client bootstrap timed out")
        .expect("bootstrap arti client with bridge config");
}

#[tokio::test(flavor = "multi_thread")]
async fn chutney_reuses_state_dir_and_connects_onion_via_tor() {
    if env::var_os("RIPDPI_TOR_CHUTNEY_E2E").is_none() {
        eprintln!("set RIPDPI_TOR_CHUTNEY_E2E=1 to run the local Chutney Tor network E2E");
        return;
    }

    let network = ChutneyNetwork::bootstrap("hs-v3-min");
    let (state_dir, cache_dir) = arti_storage_dirs(&network.arti_config_path());
    let dirs = prepare_arti_state_dirs(&state_dir, &cache_dir).expect("prepare chutney Arti storage");
    let marker = dirs.state_dir().join("ripdpi-state-persistence-marker");

    let config = load_arti_config_from_toml(network.arti_config_path()).expect("load chutney arti config");
    let client = timeout(Duration::from_secs(90), TorRelayClient::create_bootstrapped(config))
        .await
        .expect("first arti client bootstrap timed out")
        .expect("first bootstrap arti client");
    fs::write(&marker, b"persist").expect("write state marker");

    let listener = TcpListener::bind(("127.0.0.1", CHUTNEY_VERIFY_PORT)).await.expect("bind onion target listener");
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.expect("accept Tor onion stream");
        let mut request = [0_u8; 4];
        socket.read_exact(&mut request).await.expect("read onion request");
        assert_eq!(&request, b"ping");
        socket.write_all(b"pong").await.expect("write onion response");
    });

    let onion = network.hidden_service_hostname();
    let target = TorTarget::new(onion, 5858).expect("chutney onion target");
    let mut stream = timeout(Duration::from_secs(30), client.connect_tcp(&target))
        .await
        .expect("Tor onion connect timed out")
        .expect("connect to Chutney onion service");
    timeout(Duration::from_secs(10), stream.write_all(b"ping"))
        .await
        .expect("write through Tor onion stream timed out")
        .expect("write through Tor onion stream");
    timeout(Duration::from_secs(10), stream.flush())
        .await
        .expect("flush through Tor onion stream timed out")
        .expect("flush through Tor onion stream");
    let mut response = [0_u8; 4];
    timeout(Duration::from_secs(10), stream.read_exact(&mut response))
        .await
        .expect("read through Tor onion stream timed out")
        .expect("read through Tor onion stream");
    assert_eq!(&response, b"pong");
    server.await.expect("onion target server task");

    assert!(directory_has_entries(dirs.cache_dir()));
    drop(client);

    let config = load_arti_config_from_toml(network.arti_config_path()).expect("reload chutney arti config");
    timeout(Duration::from_secs(90), TorRelayClient::create_bootstrapped(config))
        .await
        .expect("second arti client bootstrap timed out")
        .expect("second bootstrap reuses Arti state");
    assert_eq!(fs::read(&marker).expect("read persisted state marker"), b"persist");
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

    fn hidden_service_hostname(&self) -> String {
        let nodes_dir = fs::canonicalize(self.data_dir.join("nodes")).expect("canonical chutney nodes dir");
        let hostname_path = find_first_file(&nodes_dir, "hostname").expect("chutney hidden service hostname");
        fs::read_to_string(hostname_path).expect("read hidden service hostname").trim().to_owned()
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

fn arti_storage_dirs(config_path: &Path) -> (PathBuf, PathBuf) {
    let config = fs::read_to_string(config_path).expect("read chutney arti config");
    let value: toml::Value = toml::from_str(&config).expect("parse chutney arti config");
    let storage = value.get("storage").and_then(toml::Value::as_table).expect("storage section");
    let state_dir = storage.get("state_dir").and_then(toml::Value::as_str).map(PathBuf::from).expect("state_dir value");
    let cache_dir = storage.get("cache_dir").and_then(toml::Value::as_str).map(PathBuf::from).expect("cache_dir value");
    (state_dir, cache_dir)
}

fn directory_has_entries(path: &Path) -> bool {
    fs::read_dir(path).expect("read storage directory").next().is_some()
}

fn find_first_file(root: &Path, name: &str) -> Option<PathBuf> {
    for entry in fs::read_dir(root).ok()? {
        let entry = entry.ok()?;
        let path = entry.path();
        if path.file_name().is_some_and(|file_name| file_name == name) {
            return Some(path);
        }
        if path.is_dir()
            && let Some(found) = find_first_file(&path, name)
        {
            return Some(found);
        }
    }
    None
}
