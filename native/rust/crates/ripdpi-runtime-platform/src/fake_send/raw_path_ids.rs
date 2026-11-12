use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_config::IpIdMode;

use crate::FakeTcpOptions;
use crate::ipv4_ids::{reserve_ipv4_identifications, reserve_stream_ipv4_identifications};

pub(crate) fn reserve_one_for_stream(stream: &TcpStream, ip_id_mode: Option<IpIdMode>) -> io::Result<Option<u16>> {
    Ok(reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next())
}

pub(crate) fn reserve_for_addresses(
    source: SocketAddr,
    target: SocketAddr,
    ip_id_mode: Option<IpIdMode>,
    packet_count: usize,
) -> Vec<u16> {
    reserve_ipv4_identifications(source, target, ip_id_mode, packet_count)
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
                        let options = prepare_fake_tcp_options(
                            &client,
                            b"primary",
                            FakeTcpOptions {
                                secondary_fake_prefix: Some(b"secondary"),
                                force_raw_original: force_original,
                                require_raw_path: require_raw,
                                ..Default::default()
                            },
                            mode,
                        )
                        .expect("prepare fake options");

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
}
