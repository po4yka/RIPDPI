use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_config::IpIdMode;

use crate::ipv4_ids::{reserve_ipv4_identifications, reserve_stream_ipv4_identifications};
use crate::FakeTcpOptions;

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
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let supports_ipv4_ids = matches!((source, target), (SocketAddr::V4(_), SocketAddr::V4(_)));
    let require_raw_path = supports_ipv4_ids && ip_id_mode.is_some();
    let force_raw_original = matches!(ip_id_mode, Some(IpIdMode::SeqGroup)) && supports_ipv4_ids;
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
