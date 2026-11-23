use std::io;
use std::net::TcpStream;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
#[cfg(feature = "privileged-packets")]
use super::{raw_path_ids, root_helper_dispatch};
use crate::{FakeTcpOptions, TcpStageWait};

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    #[cfg(feature = "privileged-packets")]
    let options = {
        let options = raw_path_ids::prepare_fake_tcp_options(stream, fake_prefix, options, ip_id_mode)?;
        if let Some(result) = root_helper_dispatch::send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            &options,
            wait,
        ) {
            return result;
        }
        options
    };
    #[cfg(not(feature = "privileged-packets"))]
    if ip_id_mode.is_some() && stream.local_addr()?.is_ipv4() && stream.peer_addr()?.is_ipv4() {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "explicit IPv4 IDs require a packet sender"));
    }
    ripdpi_privileged_ops::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _options: FakeTcpOptions<'_>,
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(all(test, not(feature = "privileged-packets"), any(target_os = "linux", target_os = "android")))]
mod tests {
    use std::io::Read as _;
    use std::net::{Shutdown, TcpListener};
    use std::time::Duration;

    use super::*;

    #[test]
    fn explicit_ipv4_id_mode_fails_before_writing() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let stream = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect");
        let (mut peer, _) = listener.accept().expect("accept");
        for mode in [IpIdMode::Zero, IpIdMode::Seq] {
            let error = send_fake_tcp(
                &stream,
                b"real",
                b"fake",
                1,
                false,
                37,
                FakeTcpOptions::default(),
                Some(mode),
                (false, Duration::ZERO),
            )
            .expect_err("explicit IPv4 IDs require a packet sender");
            assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        }

        stream.shutdown(Shutdown::Write).expect("finish stream");
        let mut bytes = Vec::new();
        peer.read_to_end(&mut bytes).expect("read peer bytes");
        assert!(bytes.is_empty());
    }
}
