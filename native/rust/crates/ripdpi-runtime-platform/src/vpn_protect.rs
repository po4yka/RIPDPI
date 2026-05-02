use std::io;
use std::os::fd::AsRawFd;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return crate::protect::protect_socket_via_callback(socket.as_raw_fd());
    }

    ripdpi_privileged_ops::protect_socket(socket, path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(socket: &T, _path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return crate::protect::protect_socket_via_callback(socket.as_raw_fd());
    }

    Ok(())
}
