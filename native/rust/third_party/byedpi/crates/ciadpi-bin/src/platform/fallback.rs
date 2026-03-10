use std::io;
use std::net::TcpStream;

use socket2::SockRef;

fn set_stream_ttl_best_effort(stream: &TcpStream, ttl: u8) {
    let socket = SockRef::from(stream);
    let _ = socket.set_ttl(ttl as u32);
    let _ = socket.set_unicast_hops_v6(ttl as u32);
}

pub fn send_fake_tcp_best_effort(
    stream: &TcpStream,
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    set_tcp_md5sig: fn(&TcpStream, u16) -> io::Result<()>,
) -> io::Result<()> {
    if ttl != 0 {
        set_stream_ttl_best_effort(stream, ttl);
    }
    if md5sig {
        let _ = set_tcp_md5sig(stream, 5);
    }
    let mut writer = stream.try_clone()?;
    std::io::Write::write_all(&mut writer, fake_prefix)?;
    if md5sig {
        let _ = set_tcp_md5sig(stream, 0);
    }
    if default_ttl != 0 {
        set_stream_ttl_best_effort(stream, default_ttl);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::TcpListener;
    use std::sync::Mutex;
    use std::time::Duration;

    static MD5_CALLS: Mutex<Vec<u16>> = Mutex::new(Vec::new());

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn record_md5sig_call(_stream: &TcpStream, key_len: u16) -> io::Result<()> {
        MD5_CALLS
            .lock()
            .expect("md5 calls mutex poisoned")
            .push(key_len);
        Ok(())
    }

    #[test]
    fn send_fake_tcp_best_effort_writes_fake_prefix() {
        let (client, mut server) = connected_pair();
        server
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("set read timeout");

        send_fake_tcp_best_effort(&client, b"fallback-fake", 8, false, 32, record_md5sig_call)
            .expect("send fake prefix");

        let mut buf = [0u8; 13];
        server.read_exact(&mut buf).expect("read fake prefix");
        assert_eq!(&buf, b"fallback-fake");
    }

    #[test]
    fn send_fake_tcp_best_effort_toggles_md5sig_around_write() {
        let (client, mut server) = connected_pair();
        server
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("set read timeout");
        MD5_CALLS.lock().expect("md5 calls mutex poisoned").clear();

        send_fake_tcp_best_effort(&client, b"x", 0, true, 0, record_md5sig_call)
            .expect("send fake prefix with md5");

        let mut byte = [0u8; 1];
        server.read_exact(&mut byte).expect("read fake byte");
        assert_eq!(byte, [b'x']);
        assert_eq!(
            *MD5_CALLS.lock().expect("md5 calls mutex poisoned"),
            vec![5, 0]
        );
    }
}
