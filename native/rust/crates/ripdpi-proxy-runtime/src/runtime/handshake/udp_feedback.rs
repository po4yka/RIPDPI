use ripdpi_ws_transport_port::ClientStream;
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

pub(super) use ripdpi_proxy_runtime_adapter::model::session::udp_feedback::{COMMAND, Nonce, encode_preface};
use ripdpi_proxy_runtime_adapter::platform::udp_error_queue;

use crate::runtime::state::RuntimeState;

pub(super) fn allowed(client: &ClientStream, state: &RuntimeState) -> bool {
    udp_error_queue::supported()
        && state.resolved_domain_targets_allowed()
        // TODO(po4yka): serve the private UDP command to a Unix peer once the Unix UDP path exists.
        && client.peer().is_ok_and(|peer| peer.ip().is_some() && peer.is_local())
}

pub(super) fn exact_source(client: &ClientStream, source: SocketAddr) -> io::Result<bool> {
    Ok(source.port() != 0 && Some(source.ip()) == client.peer()?.ip())
}

pub(super) fn new_nonce() -> io::Result<Nonce> {
    let mut nonce = [0; 16];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut nonce)?;
    Ok(nonce)
}

pub(super) fn reject(client: &mut ClientStream) -> io::Result<()> {
    let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
    client
        .write_all(RuntimeState::encode_socks5_reply(RuntimeState::socks5_command_unsupported_code(), fail).as_bytes())
}
