pub use ripdpi_runtime_platform::*;

pub mod relay {
    use std::io;
    use std::net::TcpStream;

    pub fn tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(stream)
    }

    pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::detach_drop_sack(stream)
    }
}

pub mod connect {
    use std::io;
    use std::net::{IpAddr, SocketAddr, TcpStream};
    use std::time::Duration;

    use socket2::{Domain, Protocol, SockAddr, Socket, Type};

    #[derive(Debug)]
    pub struct TcpConnectError {
        pub source: io::Error,
        pub tcp_total_retransmissions: Option<u32>,
        pub tcp_fast_open_enabled: bool,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub enum ProtectObservationOutcome {
        Succeeded,
        Rejected,
        Error,
    }

    pub trait TcpConnectObserver {
        fn on_socket_created(&self) {}

        fn on_protect_attempted(&self) {}

        fn on_protect_finished(&self, _outcome: ProtectObservationOutcome) {}
    }

    pub fn protect_socket(socket: &Socket, protect_path: &str) -> io::Result<()> {
        ripdpi_runtime_platform::vpn::protect_proxy_socket(socket, Some(protect_path))
    }

    pub fn set_rcvbuf(socket: &Socket, rcvbuf: u32) -> io::Result<()> {
        ripdpi_runtime_platform::socket::set_rcvbuf(socket, rcvbuf)
    }

    pub fn tcp_total_retransmissions(socket: &Socket) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(socket)
    }

    pub fn connect_tcp_stream(
        target: SocketAddr,
        bind_ip: IpAddr,
        protect_path: Option<&str>,
        tfo: bool,
        connect_timeout: Option<Duration>,
        pre_connect_rcvbuf: Option<u32>,
    ) -> Result<TcpStream, TcpConnectError> {
        connect_tcp_stream_observed(target, bind_ip, protect_path, tfo, connect_timeout, pre_connect_rcvbuf, None)
    }

    pub fn connect_tcp_stream_observed(
        target: SocketAddr,
        bind_ip: IpAddr,
        protect_path: Option<&str>,
        tfo: bool,
        connect_timeout: Option<Duration>,
        pre_connect_rcvbuf: Option<u32>,
        observer: Option<&dyn TcpConnectObserver>,
    ) -> Result<TcpStream, TcpConnectError> {
        let socket = new_tcp_socket(target, tfo)?;
        if let Some(observer) = observer {
            observer.on_socket_created();
        }
        if let Some(path) = protect_path {
            if let Some(observer) = observer {
                observer.on_protect_attempted();
            }
            protect_socket(&socket, path)
                .inspect(|()| {
                    if let Some(observer) = observer {
                        observer.on_protect_finished(ProtectObservationOutcome::Succeeded);
                    }
                })
                .map_err(|source| {
                    if let Some(observer) = observer {
                        let outcome = if source.kind() == io::ErrorKind::PermissionDenied {
                            ProtectObservationOutcome::Rejected
                        } else {
                            ProtectObservationOutcome::Error
                        };
                        observer.on_protect_finished(outcome);
                    }
                    connect_error(source, tfo)
                })?;
        }
        if tfo {
            enable_tcp_fastopen_if_supported(&socket).map_err(|source| connect_error(source, tfo))?;
        }
        bind_socket(&socket, bind_ip, target).map_err(|source| connect_error(source, tfo))?;
        if let Some(rcvbuf) = pre_connect_rcvbuf {
            let _ = set_rcvbuf(&socket, rcvbuf);
        }
        let connect_started = std::time::Instant::now();
        tracing::debug!(
            target = %target,
            bind_ip = %bind_ip,
            tcp_fast_open = tfo,
            protected = protect_path.is_some(),
            "ripdpi upstream connect start"
        );
        let connect_result = if let Some(timeout) = connect_timeout {
            socket.connect_timeout(&SockAddr::from(target), timeout)
        } else {
            socket.connect(&SockAddr::from(target))
        };
        if let Err(source) = connect_result {
            let tcp_total_retransmissions = tcp_total_retransmissions(&socket).ok().flatten();
            tracing::warn!(
                target = %target,
                bind_ip = %bind_ip,
                tcp_fast_open = tfo,
                protected = protect_path.is_some(),
                elapsed_ms = connect_started.elapsed().as_millis() as u64,
                "ripdpi upstream connect failed: {source}"
            );
            return Err(TcpConnectError { source, tcp_total_retransmissions, tcp_fast_open_enabled: tfo });
        }
        tracing::debug!(
            target = %target,
            bind_ip = %bind_ip,
            tcp_fast_open = tfo,
            protected = protect_path.is_some(),
            elapsed_ms = connect_started.elapsed().as_millis() as u64,
            "ripdpi upstream connect established"
        );
        let stream: TcpStream = socket.into();
        if let Err(err) = stream.set_nodelay(true) {
            tracing::debug!("set_nodelay on upstream socket failed (non-fatal): {err}");
        }
        Ok(stream)
    }

    pub fn enable_tcp_fastopen_connect(socket: &Socket) -> io::Result<()> {
        ripdpi_runtime_platform::socket::enable_tcp_fastopen_connect(socket)
    }

    pub fn should_ignore_android_tfo_error(err: &io::Error) -> bool {
        matches!(
            err.raw_os_error(),
            Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES | libc::EINVAL)
        )
    }

    pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::attach_drop_sack(stream)
    }

    pub fn set_tcp_window_clamp(stream: &TcpStream, clamp: u32) -> io::Result<()> {
        ripdpi_runtime_platform::socket::set_tcp_window_clamp(stream, clamp)
    }

    pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::attach_strip_timestamps(stream)
    }

    pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
        ripdpi_runtime_platform::tcp::tcp_round_trip_time_ms(stream)
    }

    pub fn record_connection_setup_duration(group_index: usize, elapsed_seconds: f64) {
        let group_label = format!("{group_index}");
        metrics::histogram!("ripdpi_connection_setup_duration_seconds", "group" => group_label).record(elapsed_seconds);
    }

    fn new_tcp_socket(target: SocketAddr, tfo: bool) -> Result<Socket, TcpConnectError> {
        let domain = match target {
            SocketAddr::V4(_) => Domain::IPV4,
            SocketAddr::V6(_) => Domain::IPV6,
        };
        Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(|source| connect_error(source, tfo))
    }

    fn enable_tcp_fastopen_if_supported(socket: &Socket) -> io::Result<()> {
        match enable_tcp_fastopen_connect(socket) {
            Ok(()) => Ok(()),
            #[cfg(target_os = "android")]
            Err(err) if should_ignore_android_tfo_error(&err) => {
                tracing::debug!("TCP Fast Open unavailable on this Android build: {err}");
                Ok(())
            }
            Err(err) => Err(err),
        }
    }

    fn bind_socket(socket: &Socket, bind_ip: IpAddr, target: SocketAddr) -> io::Result<()> {
        if is_unspecified(bind_ip) {
            return Ok(());
        }
        let bind_addr = match (bind_ip, target) {
            (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
            (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
            _ => {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family"));
            }
        };
        socket.bind(&SockAddr::from(bind_addr))
    }

    fn is_unspecified(ip: IpAddr) -> bool {
        match ip {
            IpAddr::V4(ip) => ip.is_unspecified(),
            IpAddr::V6(ip) => ip.is_unspecified(),
        }
    }

    fn connect_error(source: io::Error, tfo: bool) -> TcpConnectError {
        TcpConnectError { source, tcp_total_retransmissions: None, tcp_fast_open_enabled: tfo }
    }
}

pub mod first_response {
    use std::io;
    use std::net::TcpStream;

    pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::ttl_ops::enable_recv_ttl(stream)
    }

    pub fn read_chunk_with_ttl(stream: &mut TcpStream, chunk: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
        ripdpi_runtime_platform::ttl_ops::read_chunk_with_ttl(stream, chunk)
    }

    pub fn tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(stream)
    }
}

pub mod udp {
    use std::io;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
    use std::time::Duration;

    use socket2::{Domain, Protocol, SockAddr, Socket, Type};

    pub fn protect_socket(socket: &Socket, protect_path: &str) -> io::Result<()> {
        ripdpi_runtime_platform::vpn::protect_proxy_socket(socket, Some(protect_path))
    }

    pub fn bind_low_port(socket: &UdpSocket, local_ip: IpAddr, start_port: u16) -> io::Result<()> {
        ripdpi_runtime_platform::socket::bind_udp_low_port(socket, local_ip, start_port).map(|_| ())
    }

    pub fn is_connection_refused(err: &io::Error) -> bool {
        err.raw_os_error() == Some(libc::ECONNREFUSED)
    }

    pub fn bind_udp_socket(bind_addr: SocketAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
        let socket = new_udp_socket(bind_addr, protect_path)?;
        socket.bind(&SockAddr::from(bind_addr))?;
        configure_udp_socket(socket)
    }

    pub fn build_udp_upstream_socket(
        target: SocketAddr,
        protect_path: Option<&str>,
        bind_low_port: bool,
    ) -> io::Result<UdpSocket> {
        let socket = new_udp_socket(target, protect_path)?;
        let socket = configure_udp_socket(socket)?;
        if bind_low_port {
            let local_ip = match target {
                SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
                SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
            };
            if let Err(err) = self::bind_low_port(&socket, local_ip, 4_096) {
                tracing::warn!(%target, %err, "failed to bind UDP flow to a low source port");
            }
        }
        socket.connect(target)?;
        socket.set_nonblocking(true)?;
        Ok(socket)
    }

    fn new_udp_socket(addr: SocketAddr, protect_path: Option<&str>) -> io::Result<Socket> {
        let domain = match addr {
            SocketAddr::V4(_) => Domain::IPV4,
            SocketAddr::V6(_) => Domain::IPV6,
        };
        let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
        socket.set_reuse_address(true)?;
        if let Some(path) = protect_path {
            protect_socket(&socket, path)?;
        }
        Ok(socket)
    }

    fn configure_udp_socket(socket: Socket) -> io::Result<UdpSocket> {
        let socket: UdpSocket = socket.into();
        socket.set_read_timeout(Some(Duration::from_millis(250)))?;
        socket.set_write_timeout(Some(Duration::from_secs(5)))?;
        Ok(socket)
    }

    #[cfg(test)]
    mod protect_tests {
        use super::*;
        use std::os::fd::AsRawFd;
        use std::sync::{Arc, Mutex};

        use ripdpi_runtime_platform::protect::{
            ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
        };

        // The protect callback lives in a single process-global slot, so two
        // tests that each register their own recorder must not run concurrently
        // — otherwise one test's recorder captures the other's fds. Serialize
        // every protect-callback test in this module on a shared lock.
        static PROTECT_TEST_LOCK: Mutex<()> = Mutex::new(());

        struct Recorder {
            fds: Mutex<Vec<i32>>,
        }
        impl ProtectCallback for Recorder {
            fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
                self.fds.lock().expect("recorder lock").push(fd);
                Ok(())
            }
        }

        // RAII so a panicking assertion still releases the process-global slot.
        struct Guard(ProtectGeneration);
        impl Drop for Guard {
            fn drop(&mut self) {
                unregister_protect_callback_if(self.0);
            }
        }

        /// G3 regression: the upstream UDP socket is protected (callback path)
        /// when a `protect_path` is configured, and is NOT protected when it is
        /// absent (proxy-only, no VPN). Asserts on the specific socket fd so it
        /// is robust to other concurrent tests sharing the process-global slot.
        #[test]
        fn upstream_udp_socket_is_protected_when_protect_path_present() {
            let _serial = PROTECT_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let recorder = Arc::new(Recorder { fds: Mutex::new(Vec::new()) });
            let generation = register_protect_callback_versioned(Arc::clone(&recorder) as Arc<dyn ProtectCallback>);
            let _guard = Guard(generation);

            // Non-loopback target; UDP `connect` only sets the default peer (no
            // network traffic), so this runs offline.
            let target: SocketAddr = "203.0.113.1:443".parse().expect("valid target");

            let protected = build_udp_upstream_socket(target, Some("/unused/when/callback/present.sock"), false)
                .expect("build protected upstream socket");
            assert!(
                recorder.fds.lock().expect("recorder lock").contains(&protected.as_raw_fd()),
                "upstream socket fd must be protected before use when protect_path is Some"
            );

            let unprotected =
                build_udp_upstream_socket(target, None, false).expect("build unprotected upstream socket");
            assert!(
                !recorder.fds.lock().expect("recorder lock").contains(&unprotected.as_raw_fd()),
                "upstream socket must NOT be protected when protect_path is None (proxy-only mode)"
            );
        }

        /// G6: the SOCKS5 UDP ASSOCIATE datapath builds two egress sockets — the
        /// control TCP to the upstream SOCKS5 server and the relay UDP socket
        /// pointed at the ASSOCIATE `BND.ADDR:BND.PORT`. Both go through the
        /// shared protected constructors (`connect::connect_tcp_stream` and
        /// `build_udp_upstream_socket`) that `runtime::udp::upstream_socks` calls.
        /// This asserts BOTH fds are protected before use when a protect_path is
        /// present, exercising the real constructors against a loopback stub
        /// SOCKS5 server that completes the ASSOCIATE handshake.
        #[test]
        fn upstream_socks_associate_protects_control_tcp_and_relay_udp_sockets() {
            use std::io::{Read, Write};
            use std::net::{TcpListener, TcpStream};
            use std::thread;

            let _serial = PROTECT_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let recorder = Arc::new(Recorder { fds: Mutex::new(Vec::new()) });
            let generation = register_protect_callback_versioned(Arc::clone(&recorder) as Arc<dyn ProtectCallback>);
            let _guard = Guard(generation);

            // Stub upstream SOCKS5 server: accept, answer no-auth, parse the UDP
            // ASSOCIATE request, and reply with a relay BND.ADDR:BND.PORT.
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind stub socks server");
            let upstream = listener.local_addr().expect("listener addr");
            let server = thread::spawn(move || {
                let (mut stream, _) = listener.accept().expect("accept control client");
                let mut greeting = [0u8; 3];
                stream.read_exact(&mut greeting).expect("read auth request");
                stream.write_all(&[0x05, 0x00]).expect("write auth response");
                let mut request = [0u8; 10];
                stream.read_exact(&mut request).expect("read associate request");
                assert_eq!(request[1], 0x03, "CMD must be UDP ASSOCIATE");
                stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x14, 0xb4]).expect("write associate reply");
                let mut sink = [0u8; 1];
                let _ = stream.read(&mut sink);
            });

            // Control TCP via the SAME protected connector the ASSOCIATE path uses.
            let mut control = super::super::connect::connect_tcp_stream(
                upstream,
                IpAddr::V4(Ipv4Addr::UNSPECIFIED),
                Some("/unused/when/callback/present.sock"),
                false,
                Some(Duration::from_secs(2)),
                None,
            )
            .expect("connect control tcp");
            let control_fd = TcpStream::as_raw_fd(&control);

            // Drive the handshake so the stub yields a relay endpoint.
            control.write_all(&[0x05, 0x01, 0x00]).expect("send greeting");
            let mut auth = [0u8; 2];
            control.read_exact(&mut auth).expect("read auth response");
            control.write_all(&[0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).expect("send associate");
            let mut reply = [0u8; 10];
            control.read_exact(&mut reply).expect("read associate reply");
            let relay_endpoint = SocketAddr::from(([127, 0, 0, 1], 5300));

            // Relay UDP socket via the SAME protected constructor, pointed at the
            // relay endpoint (NOT the final target).
            let relay = build_udp_upstream_socket(relay_endpoint, Some("/unused/when/callback/present.sock"), false)
                .expect("build protected relay socket");
            let relay_fd = relay.as_raw_fd();

            let protected = recorder.fds.lock().expect("recorder lock");
            assert!(protected.contains(&control_fd), "control TCP fd must be protected before the ASSOCIATE handshake");
            assert!(protected.contains(&relay_fd), "relay UDP fd must be protected before use");
            drop(control);
            server.join().expect("join stub socks server");
        }
    }
}

pub mod handshake {
    use std::io;
    use std::net::{SocketAddr, TcpStream};

    pub fn original_destination(stream: &TcpStream) -> io::Result<SocketAddr> {
        ripdpi_runtime_platform::socket::original_dst(stream)
    }
}

pub mod listener {
    use std::io;
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::time::Duration;

    use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

    pub fn detect_default_ttl() -> io::Result<u8> {
        ripdpi_runtime_platform::capability::detect_default_ttl()
    }

    pub fn bind_tcp_listener(listen_addr: SocketAddr) -> io::Result<TcpListener> {
        let domain = if listen_addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
        let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
        socket.set_reuse_address(true)?;
        socket.bind(&SockAddr::from(listen_addr))?;
        socket.listen(1024)?;
        let listener: TcpListener = socket.into();
        listener.set_nonblocking(true)?;
        Ok(listener)
    }

    pub fn close_rejected_client(client: &TcpStream) {
        let _ = SockRef::from(client).set_linger(Some(Duration::ZERO));
    }
}

pub mod warmup {
    use std::io;
    use std::net::TcpStream;

    pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::ttl_ops::enable_recv_ttl(stream)
    }
}

pub mod process {
    use std::fs::{self, File, OpenOptions};
    use std::io;
    use std::io::Write;
    use std::path::{Path, PathBuf};

    use nix::fcntl::{Flock, FlockArg};

    use crate::model::config::ProcessSettings;

    pub struct ProcessGuard {
        _pid_file: Option<PidFileGuard>,
    }

    impl ProcessGuard {
        pub fn prepare(settings: ProcessSettings) -> io::Result<Self> {
            let pid_file_path = settings.pid_file_path;
            if settings.daemonize {
                daemonize()?;
            }
            // Both modes converge on the same flock-backed pid file guard:
            // the exclusive nonblocking lock doubles as single-instance
            // enforcement, and Drop releases the lock before unlinking.
            let pid_file = pid_file_path.map(|path| PidFileGuard::create(&path)).transpose()?;
            Ok(Self { _pid_file: pid_file })
        }
    }

    pub fn install_shutdown_signal_handlers() -> io::Result<()> {
        ripdpi_runtime_platform::capability::install_shutdown_signal_handlers()
    }

    pub fn shutdown_requested() -> bool {
        ripdpi_runtime_platform::capability::shutdown_requested()
    }

    pub fn reset_shutdown_request() {
        ripdpi_runtime_platform::capability::reset_shutdown_request();
    }

    pub fn request_shutdown() {
        ripdpi_runtime_platform::capability::request_shutdown();
    }

    pub fn detected_parallelism(fallback: usize) -> usize {
        ripdpi_runtime_platform::capability::detected_parallelism(fallback)
    }

    #[cfg(feature = "daemonize")]
    fn daemonize() -> io::Result<()> {
        ripdpi_runtime_platform::capability::daemonize()
    }

    #[cfg(not(feature = "daemonize"))]
    fn daemonize() -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "daemonization support is disabled in this build"))
    }

    struct PidFileGuard {
        file: Option<Flock<File>>,
        path: PathBuf,
    }

    impl PidFileGuard {
        fn create(path: &Path) -> io::Result<Self> {
            let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
            let lock = Flock::lock(file.try_clone()?, FlockArg::LockExclusiveNonblock)
                .map_err(|(_, error)| io::Error::from(error))?;

            file.set_len(0)?;
            write!(file, "{}", std::process::id())?;
            file.flush()?;

            Ok(Self { file: Some(lock), path: path.to_path_buf() })
        }
    }

    impl Drop for PidFileGuard {
        fn drop(&mut self) {
            // Release the advisory lock (and close the fd) BEFORE unlinking
            // the pid file. Linux flock is per-fd, so the inverse order is
            // not UB, but it leaves a small window in which another process
            // may `open(path, CREATE)` + `flock` on a fresh inode while we
            // still hold the lock on the orphaned one. Dropping the
            // `Flock<File>` first closes that window: any new claimant
            // observes either the live pid file (we still hold it) or
            // post-unlink emptiness (we're fully gone).
            if let Some(mut file) = self.file.take() {
                let _ = file.flush();
                drop(file);
            }
            let _ = fs::remove_file(&self.path);
        }
    }

    #[cfg(all(test, unix))]
    mod pid_file_guard_tests {
        //! Regressions for soundness issue #31 (incorrect field/drop
        //! order). `PidFileGuard::drop` MUST release the `Flock<File>`
        //! before unlinking the pid file so the lock-release-then-unlink
        //! teardown order matches the create-then-lock build order.
        //! Without this discipline, a sibling process could acquire a
        //! fresh inode + flock on the same path during the gap between
        //! `remove_file` and the implicit field drop, and the original
        //! guard would still appear to hold the lock against the
        //! orphaned inode.
        use super::PidFileGuard;
        use std::fs;
        use std::path::PathBuf;
        use std::sync::atomic::{AtomicU64, Ordering};

        static UNIQ: AtomicU64 = AtomicU64::new(0);

        fn unique_path(label: &str) -> PathBuf {
            let n = UNIQ.fetch_add(1, Ordering::Relaxed);
            std::env::temp_dir().join(format!("ripdpi-pidguard-{}-{}-{}.pid", std::process::id(), label, n))
        }

        #[test]
        fn create_writes_pid_and_drop_removes_file() {
            let path = unique_path("write-remove");
            {
                let guard = PidFileGuard::create(&path).expect("create");
                let contents = fs::read_to_string(&path).expect("read pid file");
                assert_eq!(
                    contents.trim(),
                    std::process::id().to_string(),
                    "pid file body must record this process id"
                );
                drop(guard);
            }
            assert!(!path.exists(), "Drop must unlink the pid file");
        }

        #[test]
        fn drop_releases_lock_so_a_second_guard_can_claim_the_path() {
            let path = unique_path("relock");
            let guard1 = PidFileGuard::create(&path).expect("first create");
            // While guard1 is alive, a second create must fail
            // (LockExclusiveNonblock returns EWOULDBLOCK on the same inode).
            assert!(PidFileGuard::create(&path).is_err(), "second create must fail while first holds the flock");
            drop(guard1);
            // After guard1 drops, the path is removed AND the flock is
            // released — a fresh guard succeeds. This is the load-bearing
            // assertion for issue #31: a stale flock on an orphaned inode
            // would NOT block a sibling claiming a fresh inode at the
            // same path, but the test verifies the conventional sequence
            // works end-to-end (file removed AND lock released cleanly).
            let guard2 = PidFileGuard::create(&path).expect("re-create after drop");
            drop(guard2);
            assert!(!path.exists(), "second guard must also clean up");
        }

        #[cfg(not(feature = "daemonize"))]
        #[test]
        fn daemonize_request_fails_when_support_is_not_compiled() {
            let result = super::ProcessGuard::prepare(crate::model::config::ProcessSettings {
                daemonize: true,
                pid_file_path: None,
            });
            let Err(error) = result else { panic!("embedded builds must reject daemonization") };

            assert_eq!(error.kind(), std::io::ErrorKind::Unsupported);
        }
    }
}
