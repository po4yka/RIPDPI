use std::io::{self, Read, Write};
use std::mem::zeroed;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV6};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, IntoRawFd, OwnedFd};
use std::os::unix::net::UnixStream;

use crate::linux::fd::{close_owned_fd, replace_fd_cloexec, storage_to_socket_addr};
use crate::linux::mmap_region::MmapRegion;

#[test]
fn replacement_fd_keeps_the_socket_private_and_usable() {
    const CHILD_FD: &str = "RIPDPI_REPLACEMENT_FD_CHILD";
    const CHILD_SOCKET: &str = "RIPDPI_REPLACEMENT_SOCKET_CHILD";
    if let Some(fd) = std::env::var_os(CHILD_FD) {
        let path = std::path::Path::new("/proc/self/fd").join(fd);
        let inherited = std::fs::read_link(path).ok();
        let original = std::env::var_os(CHILD_SOCKET).map(std::path::PathBuf::from);
        assert_ne!(inherited, original, "exec child inherited the replacement socket");
        return;
    }

    let (mut target, _old_peer) = super::connected_pair();
    let (replacement, mut peer) = super::connected_pair();
    // SAFETY: replacement transfers its owned descriptor; target retains its live descriptor.
    unsafe { crate::swap_replacement_fd(target.as_raw_fd(), replacement.into_raw_fd()) }
        .expect("install replacement socket");
    // SAFETY: target is live and F_GETFD only reads its descriptor flags.
    let flags = unsafe { libc::fcntl(target.as_raw_fd(), libc::F_GETFD) };
    assert_ne!(flags & libc::FD_CLOEXEC, 0, "replacement must remain close-on-exec");

    let socket = std::fs::read_link(format!("/proc/self/fd/{}", target.as_raw_fd())).expect("socket identity");
    let child = std::process::Command::new(std::env::current_exe().expect("test executable"))
        .args(["--exact", "linux::tests::fd::replacement_fd_keeps_the_socket_private_and_usable"])
        .env(CHILD_FD, target.as_raw_fd().to_string())
        .env(CHILD_SOCKET, socket)
        .status()
        .expect("exec descriptor observer");
    assert!(child.success(), "replacement socket must not cross exec");

    peer.write_all(b"ok").expect("write through replacement");
    let mut bytes = [0; 2];
    target.read_exact(&mut bytes).expect("read through replacement");
    assert_eq!(&bytes, b"ok");
    drop(target);
    peer.set_read_timeout(Some(std::time::Duration::from_secs(1))).expect("set EOF deadline");
    assert_eq!(peer.read(&mut bytes).expect("temporary replacement must close"), 0);
}

#[test]
fn replacement_fd_with_the_same_number_keeps_the_caller_socket() {
    let (target, mut peer) = super::connected_pair();
    let raw = target.into_raw_fd();
    // SAFETY: raw is live and uniquely owned; the caller retains this same-number target.
    let result = unsafe { crate::swap_replacement_fd(raw, raw) };
    // SAFETY: querying any descriptor number is valid, including an erroneous closed target.
    let flags = unsafe { libc::fcntl(raw, libc::F_GETFD) };
    // Adopt only a descriptor the kernel still reports as live, including on a failed assertion.
    let mut target = (flags >= 0).then(|| {
        // SAFETY: raw is live and this is its only owning handle after into_raw_fd above.
        unsafe { std::net::TcpStream::from_raw_fd(raw) }
    });
    result.expect("same-number replacement");
    assert_ne!(flags, -1, "same-number replacement closed the caller-owned target");
    assert_ne!(flags & libc::FD_CLOEXEC, 0);
    peer.write_all(b"ok").expect("write through retained socket");
    let mut bytes = [0; 2];
    target.as_mut().expect("live target").read_exact(&mut bytes).expect("read retained socket");
    assert_eq!(&bytes, b"ok");
}

#[test]
fn failed_replacement_closes_the_transferred_fd_and_keeps_the_target() {
    const CHILD: &str = "RIPDPI_REPLACEMENT_LIMIT_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let child = std::process::Command::new(std::env::current_exe().expect("test executable"))
            .args([
                "--exact",
                "linux::tests::fd::failed_replacement_closes_the_transferred_fd_and_keeps_the_target",
                "--test-threads=1",
            ])
            .env(CHILD, "1")
            .status()
            .expect("run isolated descriptor-limit test");
        assert!(child.success());
        return;
    }

    let (target, _target_peer) = super::connected_pair();
    let (replacement, mut peer) = super::connected_pair();
    let mut original = libc::rlimit { rlim_cur: 0, rlim_max: 0 };
    // SAFETY: original is a writable rlimit in this isolated test subprocess.
    assert_eq!(unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut original) }, 0);
    let limit = original.rlim_cur.min(128) - 1;
    let high = nix::fcntl::fcntl(
        target.as_fd(),
        nix::fcntl::FcntlArg::F_DUPFD_CLOEXEC(i32::try_from(limit).expect("bounded test limit")),
    )
    .expect("duplicate target below the original limit");
    // SAFETY: fcntl returned a fresh descriptor and ownership transfers to this handle.
    let high = unsafe { OwnedFd::from_raw_fd(high) };
    let limited = libc::rlimit { rlim_cur: limit, rlim_max: original.rlim_max };
    // SAFETY: only this subprocess's soft limit changes; existing descriptors remain valid.
    assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limited) }, 0);
    // SAFETY: both descriptors remain live; replacement transfers ownership to the call.
    let result = unsafe { crate::swap_replacement_fd(high.as_raw_fd(), replacement.into_raw_fd()) };
    // SAFETY: the original hard limit is unchanged, so restoring the old soft limit is permitted.
    assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &original) }, 0);

    assert_eq!(result.expect_err("target number exceeds the lowered limit").raw_os_error(), Some(libc::EBADF));
    // SAFETY: high remains owned and live; F_GETFD reads its descriptor flags.
    assert!(unsafe { libc::fcntl(high.as_raw_fd(), libc::F_GETFD) } >= 0, "failed replacement closed target");
    peer.set_read_timeout(Some(std::time::Duration::from_secs(1))).expect("set EOF deadline");
    assert_eq!(peer.read(&mut [0]).expect("failed replacement must release transferred fd"), 0);
}

#[test]
fn replace_fd_cloexec_replaces_target_and_close_owned_fd_releases_transient_source() {
    let (mut target_stream, _target_peer) = UnixStream::pair().expect("create target pair");
    let (source_stream, mut source_peer) = UnixStream::pair().expect("create source pair");
    let source_raw = source_stream.into_raw_fd();
    // SAFETY: we just produced `source_raw` from `into_raw_fd`; ownership
    // transfers to this `OwnedFd`, which will close the fd via
    // `close_owned_fd` below.
    let source = unsafe { OwnedFd::from_raw_fd(source_raw) };

    replace_fd_cloexec(source.as_fd(), target_stream.as_fd()).expect("replace target fd");
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

    for (ip, scope_id) in [(Ipv6Addr::LOCALHOST, 0), (Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0x1234), 42)] {
        // SAFETY: see the IPv4 storage initialization above.
        let mut storage6 = unsafe { zeroed::<libc::sockaddr_storage>() };
        // SAFETY: sockaddr_storage has sufficient size/alignment for sockaddr_in6;
        // this test immediately sets the AF_INET6 fields before parsing.
        let sin6 = unsafe { &mut *(&mut storage6 as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in6>() };
        sin6.sin6_family = libc::AF_INET6 as libc::sa_family_t;
        sin6.sin6_port = 8443u16.to_be();
        sin6.sin6_addr = libc::in6_addr { s6_addr: ip.octets() };
        sin6.sin6_scope_id = scope_id;
        assert_eq!(
            storage_to_socket_addr(&storage6).expect("parse ipv6 sockaddr"),
            SocketAddr::V6(SocketAddrV6::new(ip, 8443, 0, scope_id))
        );
    }
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

#[test]
fn protect_socket_connect_is_bounded_when_the_server_never_accepts() {
    use socket2::{Domain, SockAddr, Socket, Type};

    let path = std::env::temp_dir().join(format!("ripdpi-protect-backlog-{}.sock", std::process::id()));
    let _ = std::fs::remove_file(&path);
    let listener = Socket::new(Domain::UNIX, Type::STREAM, None).expect("socket");
    listener.bind(&SockAddr::unix(&path).expect("address")).expect("bind");
    // The server never accepts, like a wedged accept thread.
    listener.listen(0).expect("listen");
    let path_text = path.to_str().expect("temp path is valid UTF-8").to_owned();

    let (done_tx, done_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("socket to protect");
        // Each call takes an accept-queue slot; once the queue is full, connect waits.
        for _ in 0..4 {
            let _ = crate::linux::fd::protect_socket(&socket, &path_text);
        }
        let _ = done_tx.send(());
    });

    let finished = done_rx.recv_timeout(std::time::Duration::from_secs(30));
    let _ = std::fs::remove_file(&path);
    assert!(finished.is_ok(), "protect_socket must not wait forever on a full accept queue");
}
