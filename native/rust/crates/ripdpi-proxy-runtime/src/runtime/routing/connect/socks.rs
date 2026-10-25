use std::io::{self, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

use super::error::ConnectAttemptError;
use crate::runtime::state::RuntimeState;
pub(in crate::runtime::routing::connect) fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
    state: &RuntimeState,
) -> io::Result<TcpStream> {
    let mut stream = super::socket::connect_socket_detailed_observed(
        upstream,
        bind_ip,
        protect_path,
        tfo,
        connect_timeout,
        None,
        state,
    )
    .map_err(ConnectAttemptError::into_io_error)?;
    stream.set_read_timeout(connect_timeout)?;
    stream.set_write_timeout(connect_timeout)?;

    let handshake_result = (|| {
        state.negotiate_upstream_socks_auth(&mut stream, upstream)?;

        let request = RuntimeState::encode_upstream_socks_connect(target);
        stream.write_all(&request)?;
        let reply = RuntimeState::read_upstream_socks_reply(&mut stream)?;
        if !RuntimeState::upstream_socks_connect_succeeded(&reply) {
            return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks connect failed"));
        }
        Ok(())
    })();

    handshake_result?;
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{self, Read};
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
        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
            &state,
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
            stream.write_all(&[0x05, 0x00]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
            &state,
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
            stream.write_all(&[0x05, 0x00]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            stream.write_all(&[0x05, 0, 0, 0x01, 127, 0, 0, 1, 0x1f, 0x90]).expect("write connect success");
        });

        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let stream = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
            &state,
        )
        .expect("connect via upstream socks");

        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_presents_configured_listener_credentials() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).expect("read greeting");
            stream.write_all(&[0x05, 0x02]).expect("select username/password");
            let mut userpass = [0u8; 13];
            stream.read_exact(&mut userpass).expect("read RFC 1929 request");
            stream.write_all(&[0x01, 0x00]).expect("accept credentials");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            stream.write_all(&[0x05, 0, 0, 0x01, 127, 0, 0, 1, 0x1f, 0x90]).expect("write connect success");
            (greeting, userpass)
        });
        let mut config = crate::runtime::config::RuntimeConfig::default();
        config.groups[0].policy.ext_socks = Some(ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig {
            addr: upstream,
            auth: Some(ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksAuth {
                username: "user".to_string(),
                password: "secret".to_string(),
            }),
        });
        let state = RuntimeState::test(config);

        connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_secs(2)),
            &state,
        )
        .expect("authenticated connect via upstream socks");

        let (greeting, userpass) = server.join().expect("join upstream socks server");
        assert_eq!(greeting, [0x05, 0x01, 0x02], "only username/password is offered");
        assert_eq!(&userpass, b"\x01\x04user\x06secret");
    }

    #[test]
    fn upstream_socks_connect_fails_when_listener_rejects_credentials() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).expect("read greeting");
            stream.write_all(&[0x05, 0x02]).expect("select username/password");
            let mut userpass = [0u8; 13];
            stream.read_exact(&mut userpass).expect("read RFC 1929 request");
            stream.write_all(&[0x01, 0x01]).expect("reject credentials");
        });
        let mut config = crate::runtime::config::RuntimeConfig::default();
        config.groups[0].policy.ext_socks = Some(ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig {
            addr: upstream,
            auth: Some(ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksAuth {
                username: "user".to_string(),
                password: "secret".to_string(),
            }),
        });
        let state = RuntimeState::test(config);

        let err = connect_via_socks(
            SocketAddr::from(([203, 0, 113, 7], 443)),
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_secs(2)),
            &state,
        )
        .expect_err("rejected credentials must fail the connect");

        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);
        server.join().expect("join upstream socks server");
    }
}
