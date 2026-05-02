use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;

use socket2::Socket;

use crate::linux::fd::dup2_fd;

use super::settings::{apply_stream_socket_settings, StreamSocketSettings};

pub(crate) fn swap_stream_to_replacement(
    stream: &TcpStream,
    replacement: &Socket,
    settings: StreamSocketSettings,
) -> io::Result<()> {
    let target_fd = stream.as_raw_fd();
    let replacement_fd = replacement.as_raw_fd();

    dup2_fd(replacement_fd, target_fd)?;
    apply_stream_socket_settings(stream, settings);
    Ok(())
}
