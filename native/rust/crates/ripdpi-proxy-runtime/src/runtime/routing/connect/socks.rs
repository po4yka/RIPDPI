use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_session::{S_ATP_I4, S_ATP_I6, S_AUTH_NONE, S_CMD_CONN, S_ER_GEN, S_VER5};

use super::socket::connect_socket;
pub(in crate::runtime::routing::connect) fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    let mut stream = connect_socket(upstream, bind_ip, protect_path, tfo, connect_timeout)?;
    stream.set_read_timeout(connect_timeout)?;
    stream.set_write_timeout(connect_timeout)?;

    let handshake_result = (|| {
        stream.write_all(&[S_VER5, 1, S_AUTH_NONE])?;
        let mut auth = [0u8; 2];
        stream.read_exact(&mut auth)?;
        if auth != [S_VER5, S_AUTH_NONE] {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream socks auth failed"));
        }

        let request = encode_upstream_socks_connect(target);
        stream.write_all(&request)?;
        let reply = read_upstream_socks_reply(&mut stream)?;
        if reply.get(1).copied().unwrap_or(S_ER_GEN) != 0 {
            return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks connect failed"));
        }
        Ok(())
    })();

    handshake_result?;
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;
    Ok(stream)
}

pub(in crate::runtime) fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

pub(in crate::runtime) fn read_upstream_socks_reply(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;
    use std::time::Instant;

    #[test]
    fn upstream_socks_auth_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept upstream socks client");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("auth stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_reply_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("connect reply stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_clears_temporary_timeouts_after_success() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            stream.write_all(&[S_VER5, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0x1f, 0x90]).expect("write connect success");
        });

        let stream = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect("connect via upstream socks");

        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
        server.join().expect("join upstream socks server");
    }
}
