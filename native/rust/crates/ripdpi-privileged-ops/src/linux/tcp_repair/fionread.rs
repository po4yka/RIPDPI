use std::io;

pub(crate) fn pending_tcp_read_bytes(fd: libc::c_int) -> io::Result<usize> {
    let mut bytes: libc::c_int = 0;
    // SAFETY: fd is a valid TCP socket fd passed by the caller and `bytes` is a stack-allocated C integer valid for FIONREAD.
    let rc = unsafe { libc::ioctl(fd, libc::FIONREAD, &mut bytes) };
    if rc == 0 {
        usize::try_from(bytes)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative pending TCP read byte count"))
    } else {
        Err(io::Error::last_os_error())
    }
}
