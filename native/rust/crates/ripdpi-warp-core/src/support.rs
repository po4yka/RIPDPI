use std::io;

pub(crate) const MAX_PACKET: usize = 65_536;

pub(crate) fn to_io_error(error: anyhow::Error) -> io::Error {
    io::Error::other(error.to_string())
}
