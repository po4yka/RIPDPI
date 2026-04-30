use std::io::{self, Read, Write};
use std::mem::zeroed;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::{AsRawFd, IntoRawFd};
use std::os::unix::net::UnixStream;
use std::slice;

use crate::linux::fd::storage_to_socket_addr;
use crate::linux::mmap_region::{alloc_region, free_region, write_region};
use crate::linux::{close_fd, dup2_fd};

#[test]
fn dup2_fd_replaces_target_and_close_fd_releases_transient_source() {
    let (mut target_stream, _target_peer) = UnixStream::pair().expect("create target pair");
    let (source_stream, mut source_peer) = UnixStream::pair().expect("create source pair");
    let target_fd = target_stream.as_raw_fd();
    let source_fd = source_stream.into_raw_fd();

    dup2_fd(source_fd, target_fd).expect("replace target fd");
    close_fd(source_fd).expect("close transient source fd");

    source_peer.write_all(b"ok").expect("write through replacement peer");
    let mut buf = [0_u8; 2];
    target_stream.read_exact(&mut buf).expect("read from replaced target");
    assert_eq!(&buf, b"ok");

    // SAFETY: `source_fd` was closed by `close_fd`, so probing it with
    // `F_GETFD` should now fail with `EBADF`.
    let rc = unsafe { libc::fcntl(source_fd, libc::F_GETFD) };
    assert_eq!(rc, -1);
    assert_eq!(io::Error::last_os_error().raw_os_error(), Some(libc::EBADF));
}

#[test]
fn storage_to_socket_addr_parses_ipv4_and_ipv6_sockaddrs() {
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    let sin = unsafe { &mut *(&mut storage as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in>() };
    sin.sin_family = libc::AF_INET as libc::sa_family_t;
    sin.sin_port = 443u16.to_be();
    sin.sin_addr = libc::in_addr { s_addr: u32::from(Ipv4Addr::new(203, 0, 113, 8)).to_be() };
    assert_eq!(
        storage_to_socket_addr(&storage).expect("parse ipv4 sockaddr"),
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 8)), 443)
    );

    let mut storage6 = unsafe { zeroed::<libc::sockaddr_storage>() };
    let sin6 = unsafe { &mut *(&mut storage6 as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in6>() };
    sin6.sin6_family = libc::AF_INET6 as libc::sa_family_t;
    sin6.sin6_port = 8443u16.to_be();
    sin6.sin6_addr = libc::in6_addr { s6_addr: Ipv6Addr::LOCALHOST.octets() };
    assert_eq!(
        storage_to_socket_addr(&storage6).expect("parse ipv6 sockaddr"),
        SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8443)
    );
}

#[test]
fn storage_to_socket_addr_rejects_unknown_families() {
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    storage.ss_family = libc::AF_UNIX as libc::sa_family_t;

    let err = storage_to_socket_addr(&storage).expect_err("reject unsupported family");
    assert_eq!(err.kind(), io::ErrorKind::InvalidData);
}

#[test]
fn alloc_and_write_region_round_trip_bytes() {
    let len = 8usize;
    let region = alloc_region(len).expect("allocate region");
    write_region(region, b"hello", len);

    let bytes = unsafe { slice::from_raw_parts(region, len) };
    assert_eq!(&bytes[..5], b"hello");
    assert_eq!(&bytes[5..], &[0, 0, 0]);

    free_region(region, len);
}
