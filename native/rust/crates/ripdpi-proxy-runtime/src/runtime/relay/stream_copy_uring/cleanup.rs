use std::io;
use std::net::{Shutdown, TcpStream};

use ripdpi_proxy_runtime_adapter::platform::relay as relay_platform;

use super::RELAY_IDLE_TIMEOUT;

pub(super) struct RelaySockets {
    pub(super) client_reader: TcpStream,
    pub(super) client_writer: TcpStream,
    pub(super) upstream_reader: TcpStream,
    pub(super) upstream_writer: TcpStream,
}

pub(super) fn configure_relay_sockets(client: &TcpStream, upstream: &TcpStream) {
    let _ = client.set_read_timeout(Some(RELAY_IDLE_TIMEOUT));
    let _ = client.set_write_timeout(None);
    let _ = upstream.set_read_timeout(Some(RELAY_IDLE_TIMEOUT));
    let _ = upstream.set_write_timeout(None);
}

pub(super) fn clone_relay_sockets(client: &TcpStream, upstream: &TcpStream) -> io::Result<RelaySockets> {
    Ok(RelaySockets {
        client_reader: clone_socket(client, "client", "reader")?,
        client_writer: clone_socket(client, "client", "writer")?,
        upstream_reader: clone_socket(upstream, "upstream", "reader")?,
        upstream_writer: clone_socket(upstream, "upstream", "writer")?,
    })
}

pub(super) fn shutdown_relay_pair(upstream: &TcpStream, client: &TcpStream) {
    let _ = upstream.shutdown(Shutdown::Both);
    let _ = client.shutdown(Shutdown::Both);
}

pub(super) fn shutdown_direction(writer: &TcpStream, reader: &TcpStream) {
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
}

pub(super) fn detach_drop_sack_if_needed(drop_sack: bool, upstream: &TcpStream) {
    if drop_sack {
        let _ = relay_platform::detach_drop_sack(upstream);
    }
}

fn clone_socket(socket: &TcpStream, role: &'static str, direction: &'static str) -> io::Result<TcpStream> {
    socket.try_clone().map_err(|e| io::Error::other(format!("clone {role} socket for relay {direction}: {e}")))
}
