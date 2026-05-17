use std::io::{self, Read, Write};
use std::mem::zeroed;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::{AsFd, FromRawFd, IntoRawFd, OwnedFd};
use std::os::unix::net::UnixStream;

use crate::linux::fd::{close_owned_fd, dup2_fd, storage_to_socket_addr};
use crate::linux::mmap_region::MmapRegion;

#[test]
fn dup2_fd_replaces_target_and_close_owned_fd_releases_transient_source() {
    let (mut target_stream, _target_peer) = UnixStream::pair().expect("create target pair");
    let (source_stream, mut source_peer) = UnixStream::pair().expect("create source pair");
    let source_raw = source_stream.into_raw_fd();
    // SAFETY: we just produced `source_raw` from `into_raw_fd`; ownership
    // transfers to this `OwnedFd`, which will close the fd via
    // `close_owned_fd` below.
    let source = unsafe { OwnedFd::from_raw_fd(source_raw) };

    dup2_fd(source.as_fd(), target_stream.as_fd()).expect("replace target fd");
    close_owned_fd(source).expect("close transient source fd");

    source_peer.write_all(b"ok").expect("write through replacement peer");
    let mut buf = [0_u8; 2];
    target_stream.read_exact(&mut buf).expect("read from replaced target");
    assert_eq!(&buf, b"ok");

    // SAFETY: `source_raw` was closed by `close_owned_fd`, so probing it
    // with `F_GETFD` should now fail with `EBADF`.
    let rc = unsafe { libc::fcntl(source_raw, libc::F_GETFD) };
    assert_eq!(rc, -1);
    assert_eq!(io::Error::last_os_error().raw_os_error(), Some(libc::EBADF));
}

#[test]
fn storage_to_socket_addr_parses_ipv4_and_ipv6_sockaddrs() {
    // SAFETY: sockaddr_storage is a plain C storage struct; zeroed bytes are a
    // valid starting point before setting the family-specific fields below.
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    // SAFETY: sockaddr_storage has sufficient size/alignment for sockaddr_in;
    // this test immediately sets the AF_INET fields before parsing.
    let sin = unsafe { &mut *(&mut storage as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in>() };
    sin.sin_family = libc::AF_INET as libc::sa_family_t;
    sin.sin_port = 443u16.to_be();
    sin.sin_addr = libc::in_addr { s_addr: u32::from(Ipv4Addr::new(203, 0, 113, 8)).to_be() };
    assert_eq!(
        storage_to_socket_addr(&storage).expect("parse ipv4 sockaddr"),
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 8)), 443)
    );

    // SAFETY: see the IPv4 storage initialization above.
    let mut storage6 = unsafe { zeroed::<libc::sockaddr_storage>() };
    // SAFETY: sockaddr_storage has sufficient size/alignment for sockaddr_in6;
    // this test immediately sets the AF_INET6 fields before parsing.
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
    // SAFETY: sockaddr_storage is a plain C storage struct; zeroed bytes are a
    // valid starting point before setting an unsupported family tag.
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    storage.ss_family = libc::AF_UNIX as libc::sa_family_t;

    let err = storage_to_socket_addr(&storage).expect_err("reject unsupported family");
    assert_eq!(err.kind(), io::ErrorKind::InvalidData);
}

#[test]
fn alloc_and_write_region_round_trip_bytes() {
    let len = 8usize;
    let mut region = MmapRegion::new(len).expect("allocate region");
    region.write(b"hello");

    let bytes = region.to_vec();
    assert_eq!(&bytes[..5], b"hello");
    assert_eq!(&bytes[5..], &[0, 0, 0]);
    // `region` drops here, unmapping.
}

#[test]
fn mmap_region_rejects_zero_length() {
    match MmapRegion::new(0) {
        Ok(_) => panic!("zero-length must fail"),
        Err(err) => assert_eq!(err.kind(), io::ErrorKind::InvalidInput),
    }
}

#[test]
fn mmap_region_write_truncates_to_region_length() {
    let mut region = MmapRegion::new(4).expect("allocate region");
    region.write(b"abcdefgh");
    let bytes = region.to_vec();
    assert_eq!(bytes, b"abcd");
}

#[test]
fn mmap_region_vmsplice_rejects_oversized_len() {
    let (_read_fd, write_fd) = nix::unistd::pipe().expect("create pipe");
    let mut region = MmapRegion::new(4).expect("allocate region");

    let err = region.vmsplice_to(write_fd.as_fd(), 5).expect_err("oversized vmsplice should fail");

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
}
