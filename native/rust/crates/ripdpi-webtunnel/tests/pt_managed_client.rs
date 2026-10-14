use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use local_network_fixture::WebTunnelFixture;
use ripdpi_webtunnel::pt::{ManagedClientEnvironment, decode_pt_auth_args, parse_client_pt_args, pt_client_transcript};

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
    let (mut child, listener) = spawn_managed_client();

    retry_connect(listener);
    stop_managed_client(&mut child);
}

/// The production PT binary must verify the bridge certificate before it sends
/// the secret WebTunnel path. The fixture serves a self-signed certificate that
/// no bundled root trusts, so an on-path attacker with such a certificate must
/// get neither a SOCKS success nor the HTTP Upgrade request.
#[test]
fn binary_rejects_untrusted_bridge_certificate_before_sending_secret_path() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let (mut child, listener) = spawn_managed_client();

    let pt_args = format!("url={};addr={};servername=localhost", fixture.url(), fixture.addr());
    parse_client_pt_args(&pt_args).expect("fixture PT args parse");
    let reply = socks5_connect_with_pt_args(listener, &pt_args);
    stop_managed_client(&mut child);

    assert_ne!(reply, 0x00, "SOCKS CONNECT must fail when the bridge certificate is not trusted");
    assert!(
        fixture.observed_requests().is_empty(),
        "the secret path must not reach a bridge with an unverified certificate: {:?}",
        fixture.observed_requests(),
    );
}

fn spawn_managed_client() -> (Child, SocketAddr) {
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
    (child, listener)
}

fn stop_managed_client(child: &mut Child) {
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

/// Drive a SOCKS5 CONNECT through the PT listener with the bridge arguments in
/// the RFC1929 username, as tor does. Returns the SOCKS reply code.
///
/// The greeting, auth, and CONNECT request go out in one write: the PT reads the
/// accepted socket without waiting, and on macOS that socket inherits the
/// listener's non-blocking mode, so a byte that arrives late reads as an error.
fn socks5_connect_with_pt_args(listener: SocketAddr, pt_args: &str) -> u8 {
    let username = u8::try_from(pt_args.len()).expect("PT args fit in one RFC1929 field");
    let target = b"example.com";
    let mut request = vec![0x05, 0x01, 0x02, 0x01, username];
    request.extend_from_slice(pt_args.as_bytes());
    request.extend_from_slice(&[0x01, 0x00]);
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, target.len() as u8]);
    request.extend_from_slice(target);
    request.extend_from_slice(&443_u16.to_be_bytes());

    let mut stream = retry_connect(listener);
    stream.set_read_timeout(Some(Duration::from_secs(20))).expect("set SOCKS read timeout");
    stream.write_all(&request).expect("write SOCKS greeting, auth, and CONNECT");

    let mut method = [0_u8; 2];
    stream.read_exact(&mut method).expect("read SOCKS method");
    assert_eq!(method, [0x05, 0x02], "PT must select username/password auth");
    let mut auth_reply = [0_u8; 2];
    stream.read_exact(&mut auth_reply).expect("read RFC1929 reply");
    assert_eq!(auth_reply, [0x01, 0x00], "PT must accept the bridge arguments");
    let mut reply = [0_u8; 10];
    stream.read_exact(&mut reply).expect("read SOCKS reply");
    reply[1]
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

fn retry_connect(listener: SocketAddr) -> TcpStream {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match TcpStream::connect(listener) {
            Ok(stream) => return stream,
            Err(error) if Instant::now() < deadline => {
                let _ = error;
                std::thread::sleep(Duration::from_millis(25));
            }
            Err(error) => panic!("connect to advertised listener {listener}: {error}"),
        }
    }
}
