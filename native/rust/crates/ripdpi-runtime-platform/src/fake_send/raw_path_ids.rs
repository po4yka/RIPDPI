use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_config::IpIdMode;

use crate::FakeTcpOptions;
use crate::ipv4_ids::{reserve_ipv4_identifications, reserve_stream_ipv4_identifications};

pub(crate) fn reserve_one_for_stream(stream: &TcpStream, ip_id_mode: Option<IpIdMode>) -> io::Result<Option<u16>> {
    validate_ipv4_id_mode(stream.local_addr()?, stream.peer_addr()?, ip_id_mode)?;
    Ok(reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next())
}

pub(crate) fn reserve_for_addresses(
    source: SocketAddr,
    target: SocketAddr,
    ip_id_mode: Option<IpIdMode>,
    packet_count: usize,
) -> io::Result<Vec<u16>> {
    validate_ipv4_id_mode(source, target, ip_id_mode)?;
    Ok(reserve_ipv4_identifications(source, target, ip_id_mode, packet_count))
}

pub(crate) fn prepare_fake_tcp_options<'a>(
    stream: &TcpStream,
    fake_prefix: &[u8],
    mut options: FakeTcpOptions<'a>,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<FakeTcpOptions<'a>> {
    if options.original_ttl == Some(0) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "original TCP TTL must be nonzero"));
    }
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    validate_ipv4_id_mode(source, target, ip_id_mode)?;
    let supports_ipv4_ids = matches!((source, target), (SocketAddr::V4(_), SocketAddr::V4(_)));
    let require_raw_path = options.require_raw_path || (supports_ipv4_ids && ip_id_mode.is_some());
    let force_raw_original =
        options.force_raw_original || (matches!(ip_id_mode, Some(IpIdMode::SeqGroup)) && supports_ipv4_ids);
    let packet_count = usize::from(!fake_prefix.is_empty())
        + usize::from(options.secondary_fake_prefix.is_some_and(|payload| !payload.is_empty()))
        + usize::from(force_raw_original || !options.orig_flags.is_empty());
    let ids = if require_raw_path {
        reserve_ipv4_identifications(source, target, ip_id_mode, packet_count)
    } else {
        Vec::new()
    };

    options.require_raw_path = require_raw_path;
    options.force_raw_original = force_raw_original;
    options.ipv4_identifications = ids;

    Ok(options)
}

fn validate_ipv4_id_mode(source: SocketAddr, target: SocketAddr, mode: Option<IpIdMode>) -> io::Result<()> {
    if matches!((source, target, mode), (SocketAddr::V4(_), SocketAddr::V4(_), Some(IpIdMode::Zero))) {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "IP_HDRINCL cannot preserve an exact zero IPv4 Identification",
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::net::TcpListener;

    use super::*;

    #[test]
    fn fake_tcp_options_preserve_original_transport_and_reserve_every_raw_packet() {
        for address in ["127.0.0.1:0", "[::1]:0"] {
            let listener = TcpListener::bind(address).expect("loopback listener");
            let client = TcpStream::connect(listener.local_addr().expect("listener address")).expect("client connect");
            let (_server, _) = listener.accept().expect("accept client");
            let is_ipv4 = client.peer_addr().expect("peer address").is_ipv4();
            for mode in [None, Some(IpIdMode::Seq), Some(IpIdMode::SeqGroup), Some(IpIdMode::Rnd), Some(IpIdMode::Zero)]
            {
                for force_original in [false, true] {
                    for require_raw in [false, true] {
                        let prepared = prepare_fake_tcp_options(
                            &client,
                            b"primary",
                            FakeTcpOptions {
                                secondary_fake_prefix: Some(b"secondary"),
                                force_raw_original: force_original,
                                require_raw_path: require_raw,
                                ..Default::default()
                            },
                            mode,
                        );
                        if is_ipv4 && mode == Some(IpIdMode::Zero) {
                            assert_eq!(prepared.expect_err("IPv4 zero ID is unsupported").kind(), io::ErrorKind::Unsupported);
                            continue;
                        }
                        let options = prepared.expect("prepare fake options");

                        let expected_original = force_original || (is_ipv4 && mode == Some(IpIdMode::SeqGroup));
                        assert_eq!(options.force_raw_original, expected_original);
                        assert_eq!(options.require_raw_path, require_raw || (is_ipv4 && mode.is_some()));
                        let expected_ids =
                            if is_ipv4 && mode.is_some() { 2 + usize::from(expected_original) } else { 0 };
                        assert_eq!(options.ipv4_identifications.len(), expected_ids);
                        assert_eq!(options.secondary_fake_prefix, Some(b"secondary".as_slice()));
                    }
                }
            }
        }
    }

    #[test]
    fn exact_zero_ipv4_mode_is_rejected_before_id_reservation() {
        let source = "192.0.2.1:12345".parse().expect("source");
        let target = "198.51.100.2:443".parse().expect("target");
        assert_eq!(
            reserve_for_addresses(source, target, Some(IpIdMode::Zero), 2).expect_err("unsupported wire ID").kind(),
            io::ErrorKind::Unsupported
        );
        assert_eq!(reserve_for_addresses(source, target, None, 2).expect("default IDs"), Vec::<u16>::new());
        assert_eq!(reserve_for_addresses(source, target, Some(IpIdMode::Seq), 2).expect("sequential IDs").len(), 2);
    }

    #[test]
    fn zero_ipv4_default_fake_and_single_packet_options_fail_before_dispatch() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let client = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
        let (_peer, _) = listener.accept().expect("accept");
        assert_eq!(
            prepare_fake_tcp_options(&client, b"fake", FakeTcpOptions::default(), Some(IpIdMode::Zero))
                .expect_err("default fake must reject zero")
                .kind(),
            io::ErrorKind::Unsupported
        );
        assert_eq!(
            reserve_one_for_stream(&client, Some(IpIdMode::Zero))
                .expect_err("single raw packet must reject zero")
                .kind(),
            io::ErrorKind::Unsupported
        );
    }

    #[test]
    fn ipv6_ignores_the_ipv4_only_zero_mode() {
        let source = "[2001:db8::1]:12345".parse().expect("source");
        let target = "[2001:db8::2]:443".parse().expect("target");
        assert!(
            reserve_for_addresses(source, target, Some(IpIdMode::Zero), 2).expect("IPv6 has no IPv4 ID").is_empty()
        );
    }
}
