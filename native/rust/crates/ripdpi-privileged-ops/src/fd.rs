use std::io;
use std::os::fd::AsRawFd;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    if let Some(path) = path { crate::linux::protect_socket(socket, path) } else { Ok(()) }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(_socket: &T, _path: Option<&str>) -> io::Result<()> {
    Ok(())
}
