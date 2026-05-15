use std::io;
use std::net::TcpStream;
use std::os::fd::AsFd;

use socket2::Socket;

use crate::linux::fd::dup2_fd;

use super::settings::{apply_stream_socket_settings, StreamSocketSettings};

pub(crate) fn swap_stream_to_replacement(
    stream: &TcpStream,
    replacement: &Socket,
    settings: StreamSocketSettings,
) -> io::Result<()> {
    // No unsafe here: both descriptors are typed and `dup2_fd` accepts
    // `BorrowedFd<'_>`. `replacement`'s fd remains alive (caller still
    // owns the `Socket`); `stream`'s fd table entry is replaced in place.
    dup2_fd(replacement.as_fd(), stream.as_fd())?;
    apply_stream_socket_settings(stream, settings);
    Ok(())
}
