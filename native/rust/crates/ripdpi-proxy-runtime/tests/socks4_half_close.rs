#![cfg(not(feature = "loom"))]

mod support;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::sync::{Mutex, PoisonError};
use std::thread;
use std::time::Duration;

use support::proxy::{ephemeral_proxy_config, start_proxy};

static TEST_LOCK: Mutex<()> = Mutex::new(());
const IO_TIMEOUT: Duration = Duration::from_secs(3);
const RESPONSE: &[u8] = b"response after client FIN";
const REQUEST: &[u8] = b"request after server FIN";

fn socks4_connect(proxy_port: u16, target_port: u16, domain: bool) -> TcpStream {
    let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect proxy");
    client.set_read_timeout(Some(IO_TIMEOUT)).expect("bound client reads");
    client.set_write_timeout(Some(IO_TIMEOUT)).expect("bound client writes");
    let [high, low] = target_port.to_be_bytes();
    let mut request = vec![4, 1, high, low];
    request.extend_from_slice(if domain { &[0, 0, 0, 1] } else { &[127, 0, 0, 1] });
    request.push(0);
    if domain {
        request.extend_from_slice(b"127.0.0.1\0");
    }
    client.write_all(&request).expect("send SOCKS4 CONNECT");
    let mut reply = [0; 8];
    client.read_exact(&mut reply).expect("read SOCKS4 reply");
    assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
    client
}

#[test]
fn empty_client_fin_preserves_the_origin_response() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for domain in [false, true] {
        response_after_client_fin(domain, b"", Duration::ZERO);
    }
}

#[test]
fn payload_and_fin_preserve_a_delayed_origin_response() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for domain in [false, true] {
        response_after_client_fin(domain, b"payload before FIN", Duration::from_millis(1100));
    }
}

fn response_after_client_fin(domain: bool, payload: &'static [u8], delay: Duration) {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    let port = origin.local_addr().expect("origin address").port();
    let server = thread::spawn(move || {
        let (mut stream, _) = origin.accept().expect("accept origin connection");
        stream.set_read_timeout(Some(IO_TIMEOUT)).expect("bound origin reads");
        stream.set_write_timeout(Some(IO_TIMEOUT)).expect("bound origin writes");
        let mut received = Vec::new();
        stream.read_to_end(&mut received).expect("origin receives client FIN");
        thread::sleep(delay);
        let sent = stream.write_all(RESPONSE);
        (received, sent)
    });
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
    let mut client = socks4_connect(proxy.port, port, domain);
    client.write_all(payload).expect("write application payload");
    client.shutdown(Shutdown::Write).expect("close client write direction");
    let mut response = Vec::new();
    let read = client.read_to_end(&mut response);
    let _ = client.shutdown(Shutdown::Both);
    drop(proxy);
    let (received, sent) = server.join().expect("join origin");
    assert_eq!(received, payload, "SOCKS4a={domain}");
    sent.expect("origin can respond after client FIN");
    read.expect("read response after client FIN");
    assert_eq!(response, RESPONSE, "SOCKS4a={domain}");
}

#[test]
fn empty_origin_fin_reaches_the_client_before_its_payload() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
        let port = origin.local_addr().expect("origin address").port();
        let server = thread::spawn(move || {
            let (mut stream, _) = origin.accept().expect("accept origin connection");
            stream.set_read_timeout(Some(IO_TIMEOUT)).expect("bound origin reads");
            stream.shutdown(Shutdown::Write).expect("close origin write direction");
            let mut received = Vec::new();
            stream.read_to_end(&mut received).expect("origin receives request after its FIN");
            received
        });
        let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
        let mut client = socks4_connect(proxy.port, port, domain);
        client.set_read_timeout(Some(Duration::from_millis(500))).expect("bound initial EOF wait");
        let eof = client.read(&mut [0]);
        client.write_all(REQUEST).expect("client can write after origin FIN");
        client.shutdown(Shutdown::Write).expect("finish request");
        let received = server.join().expect("join origin");
        let _ = client.shutdown(Shutdown::Both);
        drop(proxy);
        assert_eq!(eof.expect("origin FIN must arrive before client payload"), 0, "SOCKS4a={domain}");
        assert_eq!(received, REQUEST, "SOCKS4a={domain}");
    }
}

#[test]
fn empty_client_reset_closes_the_origin_connection() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
        let port = origin.local_addr().expect("origin address").port();
        let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
        let client = socks4_connect(proxy.port, port, domain);
        let (mut stream, _) = origin.accept().expect("accept origin connection");
        stream.set_read_timeout(Some(Duration::from_secs(1))).expect("bound reset propagation");
        socket2::SockRef::from(&client).set_linger(Some(Duration::ZERO)).expect("reset client on drop");
        drop(client);
        let terminal = stream.read(&mut [0]);
        drop(proxy);
        assert!(
            matches!(terminal, Ok(0))
                || terminal.as_ref().is_err_and(|err| matches!(
                    err.kind(),
                    std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::ConnectionAborted
                )),
            "SOCKS4a={domain}: origin must close after client RST: {terminal:?}"
        );
    }
}
