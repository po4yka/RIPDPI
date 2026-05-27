use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::net::{SocketAddr, TcpStream};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use ripdpi_webtunnel::pt::{decode_pt_auth_args, parse_client_pt_args, pt_client_transcript, ManagedClientEnvironment};

#[test]
fn managed_client_transcript_advertises_webtunnel_socks5() {
    let env = managed_env("webtunnel");
    let config = ManagedClientEnvironment::parse(&env).expect("managed client env");
    let transcript = pt_client_transcript(&config, "127.0.0.1:43123".parse().expect("listener address"));

    assert_eq!(
        transcript,
        vec!["VERSION 1".to_owned(), "CMETHOD webtunnel socks5 127.0.0.1:43123".to_owned(), "CMETHODS DONE".to_owned(),],
        "PT spec client setup requires VERSION, CMETHOD, and CMETHODS DONE; RIPDPI parses the CMETHOD line",
    );
}

#[test]
fn managed_client_transcript_omits_cmethod_for_unrequested_transport() {
    let env = managed_env("snowflake,obfs4");
    let config = ManagedClientEnvironment::parse(&env).expect("managed client env");
    let transcript = pt_client_transcript(&config, "127.0.0.1:43123".parse().expect("listener address"));

    assert_eq!(transcript, vec!["VERSION 1".to_owned(), "CMETHODS DONE".to_owned()]);
}

#[test]
fn managed_client_env_rejects_missing_or_unsupported_version() {
    let mut missing = managed_env("webtunnel");
    missing.remove("TOR_PT_MANAGED_TRANSPORT_VER");
    assert_eq!(
        ManagedClientEnvironment::parse(&missing).expect_err("missing version").to_string(),
        "TOR_PT_MANAGED_TRANSPORT_VER is required",
    );

    let mut unsupported = managed_env("webtunnel");
    unsupported.insert("TOR_PT_MANAGED_TRANSPORT_VER".to_owned(), "2".to_owned());
    assert_eq!(
        ManagedClientEnvironment::parse(&unsupported).expect_err("unsupported version").to_string(),
        "unsupported TOR_PT_MANAGED_TRANSPORT_VER: 2",
    );
}

#[test]
fn pt_auth_args_match_ripdpi_kotlin_split_contract() {
    assert_eq!(
        decode_pt_auth_args(b"url=https://front.example/secret", &[0x00]).expect("single field"),
        "url=https://front.example/secret",
    );

    let mut payload = "url=https://front.example/secret;utls=hellochrome_auto;pad=".to_owned();
    payload.push_str(&"x".repeat(260));
    let bytes = payload.as_bytes();
    let decoded = decode_pt_auth_args(&bytes[..255], &bytes[255..]).expect("split RFC1929 fields");
    assert_eq!(decoded, payload);
}

#[test]
fn pt_client_args_parse_escaped_semicolon_and_backslash() {
    let config = parse_client_pt_args(
        r"url=https://front.example/a\;b;utls=hellochrome_auto;servername=real.example;cert=abc\\def",
    )
    .expect("client PT args");

    assert_eq!(config.url, "https://front.example/a;b");
    assert_eq!(config.secret_path, "/a;b");
    assert_eq!(config.servername, "real.example");
    assert_eq!(config.utls, "hellochrome_auto");
    assert_eq!(config.cert.as_deref(), Some(r"abc\def"));
}

#[test]
fn binary_managed_client_prints_listener_and_exits_on_stdin_close() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_ripdpi-webtunnel"))
        .env("TOR_PT_MANAGED_TRANSPORT_VER", "1")
        .env("TOR_PT_STATE_LOCATION", temp_state_dir())
        .env("TOR_PT_CLIENT_TRANSPORTS", "webtunnel")
        .env("TOR_PT_EXIT_ON_STDIN_CLOSE", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn ripdpi-webtunnel");

    let stdout = child.stdout.take().expect("child stdout");
    let mut reader = BufReader::new(stdout);
    let mut lines = Vec::new();
    for _ in 0..3 {
        let mut line = String::new();
        reader.read_line(&mut line).expect("read PT line");
        lines.push(line.trim_end().to_owned());
    }

    assert_eq!(lines[0], "VERSION 1");
    let listener = lines[1]
        .strip_prefix("CMETHOD webtunnel socks5 ")
        .expect("CMETHOD webtunnel socks5 line")
        .parse::<SocketAddr>()
        .expect("listener socket address");
    assert_eq!(lines[2], "CMETHODS DONE");

    retry_connect(listener);
    drop(child.stdin.take());

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let Some(status) = child.try_wait().expect("poll child") {
            assert!(status.success(), "child should exit successfully after stdin close: {status}");
            break;
        }
        assert!(Instant::now() < deadline, "child did not exit after stdin close");
        std::thread::sleep(Duration::from_millis(25));
    }
}

fn managed_env(transports: &str) -> BTreeMap<String, String> {
    BTreeMap::from([
        ("TOR_PT_MANAGED_TRANSPORT_VER".to_owned(), "1".to_owned()),
        ("TOR_PT_STATE_LOCATION".to_owned(), temp_state_dir()),
        ("TOR_PT_CLIENT_TRANSPORTS".to_owned(), transports.to_owned()),
        ("TOR_PT_EXIT_ON_STDIN_CLOSE".to_owned(), "1".to_owned()),
    ])
}

fn temp_state_dir() -> String {
    std::env::temp_dir().join(format!("ripdpi-webtunnel-pt-{}", std::process::id())).to_string_lossy().into_owned()
}

fn retry_connect(listener: SocketAddr) {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match TcpStream::connect(listener) {
            Ok(_) => return,
            Err(error) if Instant::now() < deadline => {
                let _ = error;
                std::thread::sleep(Duration::from_millis(25));
            }
            Err(error) => panic!("connect to advertised listener {listener}: {error}"),
        }
    }
}
