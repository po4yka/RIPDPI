use ripdpi_ws_transport_port::{ClientPeer, ClientStream};
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

pub(super) use ripdpi_proxy_runtime_adapter::model::session::udp_feedback::{COMMAND, Nonce, encode_preface};
use ripdpi_proxy_runtime_adapter::platform::udp_error_queue;

use crate::runtime::state::RuntimeState;

pub(super) fn allowed(client: &ClientStream, state: &RuntimeState) -> bool {
    state.resolved_domain_targets_allowed()
        && match client.peer() {
            // Without an error queue the association only lacks remote-error frames.
            Ok(ClientPeer::LocalPrivate) => true,
            Ok(peer) => udp_error_queue::supported() && peer.is_local(),
            Err(_) => false,
        }
}

pub(super) fn exact_source(client: &ClientStream, source: SocketAddr) -> io::Result<bool> {
    Ok(match client.peer()?.ip() {
        Some(ip) => source.port() != 0 && source.ip() == ip,
        None => source.ip().is_unspecified() && source.port() == 0,
    })
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
