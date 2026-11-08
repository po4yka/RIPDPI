use super::*;
use std::io::Read;
use std::net::{Ipv4Addr, TcpListener};
use std::thread;
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksAuth, UpstreamSocksConfig};

#[derive(Clone, Copy, PartialEq, Eq)]
enum SlowPhase {
    Greeting,
    Password,
    Reply,
}

fn drip(stream: &mut TcpStream, bytes: &[u8], delay: Duration) -> io::Result<()> {
    for byte in bytes {
        thread::sleep(delay);
        stream.write_all(&[*byte])?;
    }
    Ok(())
}

fn serve(mut stream: TcpStream, phase: SlowPhase) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    let method = if phase == SlowPhase::Password { 2 } else { 0 };
    let mut greeting = [0; 3];
    stream.read_exact(&mut greeting)?;
    assert_eq!(greeting, [5, 1, method]);
    if phase == SlowPhase::Greeting {
        drip(&mut stream, &[5, method], Duration::from_millis(80))?;
    } else {
        stream.write_all(&[5, method])?;
    }
    if phase == SlowPhase::Password {
        let mut auth = [0; 5];
        stream.read_exact(&mut auth)?;
        assert_eq!(auth, [1, 1, b'u', 1, b'p']);
        drip(&mut stream, &[1, 0], Duration::from_millis(80))?;
    }
    let mut request = [0; 10];
    stream.read_exact(&mut request)?;
    assert_eq!(request, [5, 1, 0, 1, 127, 0, 0, 1, 0, 53]);
    let reply = [5, 0, 0, 1, 127, 0, 0, 1, 0, 1];
    if phase == SlowPhase::Reply {
        drip(&mut stream, &reply, Duration::from_millis(40))
    } else {
        stream.write_all(&reply)
    }
}

fn run(phase: SlowPhase, timeout: Option<Duration>) -> io::Result<TcpStream> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
    let upstream = listener.local_addr().expect("upstream endpoint");
    let peer = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept upstream");
        if let Err(error) = serve(stream, phase) {
            assert!(matches!(
                error.kind(),
                io::ErrorKind::BrokenPipe | io::ErrorKind::ConnectionReset | io::ErrorKind::UnexpectedEof
            ));
        }
    });
    let mut config = RuntimeConfig::default();
    if phase == SlowPhase::Password {
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig {
            addr: upstream,
            auth: Some(UpstreamSocksAuth { username: "u".into(), password: "p".into() }),
        });
    }
    let state = RuntimeState::test(config);
    let started = Instant::now();
    let result = connect_via_socks(
        SocketAddr::from(([127, 0, 0, 1], 53)),
        upstream,
        IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        None,
        false,
        timeout,
        &state,
    );
    let elapsed = started.elapsed();
    peer.join().expect("join upstream");
    if timeout.is_some() {
        assert!(elapsed < Duration::from_millis(300), "setup exceeded its total deadline: {elapsed:?}");
    }
    result
}

#[test]
fn upstream_socks_slow_setup_uses_one_deadline() {
    for phase in [SlowPhase::Greeting, SlowPhase::Password, SlowPhase::Reply] {
        let error = run(phase, Some(Duration::from_millis(120))).expect_err("slow setup must time out");
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    }
}

#[test]
fn upstream_socks_setup_without_timeout_accepts_split_replies() {
    let stream = run(SlowPhase::Reply, None).expect("unbounded setup accepts the complete reply");
    assert_eq!(stream.read_timeout().expect("read timeout"), None);
    assert_eq!(stream.write_timeout().expect("write timeout"), None);
}
