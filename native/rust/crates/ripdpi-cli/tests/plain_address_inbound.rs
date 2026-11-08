use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const WAIT: Duration = Duration::from_secs(5);

struct RunningCli(Child);

impl Drop for RunningCli {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn spawn_cli(port: u16, extra_args: &[&str]) -> RunningCli {
    RunningCli(
        Command::new(env!("CARGO_BIN_EXE_ripdpi"))
            .args(["--ip", "127.0.0.1", "--port", &port.to_string(), "--shadowsocks-plain"])
            .args(extra_args)
            // Explicit configuration must not depend on ambient plugin variables.
            .env("SS_LOCAL_PORT", "1")
            .env("SS_PLUGIN_OPTIONS", "--invalid-plugin-option")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .expect("launch plaintext CLI"),
    )
}

fn reserve_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0)).expect("reserve CLI port").local_addr().expect("CLI address").port()
}

fn connect_cli(proxy: &mut RunningCli, address: SocketAddr) -> TcpStream {
    let deadline = Instant::now() + WAIT;
    loop {
        assert!(
            proxy.0.try_wait().expect("check CLI process").is_none(),
            "CLI exited before plaintext input was ready"
        );
        if let Ok(stream) = TcpStream::connect_timeout(&address, Duration::from_millis(100)) {
            stream.set_read_timeout(Some(WAIT)).expect("bound client reads");
            return stream;
        }
        assert!(Instant::now() < deadline, "CLI did not start its listener");
        thread::sleep(Duration::from_millis(5));
    }
}

fn accept_origin(listener: &TcpListener) -> TcpStream {
    let deadline = Instant::now() + WAIT;
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).expect("blocking accepted stream");
                stream.set_read_timeout(Some(WAIT)).expect("bound origin reads");
                return stream;
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                assert!(Instant::now() < deadline, "plaintext request did not reach origin");
                thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("accept origin: {error}"),
        }
    }
}

fn wait_for_exit(proxy: &mut RunningCli) -> ExitStatus {
    let deadline = Instant::now() + WAIT;
    loop {
        if let Some(status) = proxy.0.try_wait().expect("check CLI exit") {
            return status;
        }
        assert!(Instant::now() < deadline, "CLI did not reject invalid listener settings");
        thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn cli_plaintext_address_input_reaches_origin() {
    for mixed in [false, true] {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind origin");
        listener.set_nonblocking(true).expect("bound origin accept");
        let origin_port = listener.local_addr().expect("origin address").port();
        let port = reserve_port();
        let mut proxy = spawn_cli(port, if mixed { &["--mixed"] } else { &[] });
        let mut client = connect_cli(&mut proxy, SocketAddr::from(([127, 0, 0, 1], port)));
        let payload = b"cli\0\xffpayload";
        let mut request = vec![0x01, 127, 0, 0, 1];
        request.extend_from_slice(&origin_port.to_be_bytes());
        request.extend_from_slice(payload);
        client.write_all(&request).expect("send independently encoded address and payload");

        let mut origin = accept_origin(&listener);
        let mut received = vec![0; payload.len()];
        origin.read_exact(&mut received).expect("read origin payload");
        assert_eq!(received, payload, "address header must not reach origin");
        origin.write_all(b"origin-reply").expect("send origin reply");
        let mut reply = [0; 12];
        client.read_exact(&mut reply).expect("read plaintext origin reply without a protocol acknowledgement");
        assert_eq!(&reply, b"origin-reply");
    }
}

#[test]
fn cli_plaintext_address_input_rejects_lan_bind() {
    let mut proxy = spawn_cli(reserve_port(), &["--ip", "0.0.0.0"]);
    assert!(!wait_for_exit(&mut proxy).success());
    let mut error = String::new();
    proxy.0.stderr.take().expect("CLI stderr").read_to_string(&mut error).expect("read CLI error");
    assert!(error.contains("non-loopback proxy listener requires auth token"), "unexpected startup error: {error}");
}
