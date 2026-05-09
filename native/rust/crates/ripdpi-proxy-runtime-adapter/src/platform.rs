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

    pub fn protect_socket(socket: &Socket, protect_path: &str) -> io::Result<()> {
        ripdpi_runtime_platform::vpn::protect_socket(socket, Some(protect_path))
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
        let socket = new_tcp_socket(target, tfo)?;
        if let Some(path) = protect_path {
            protect_socket(&socket, path).map_err(|source| connect_error(source, tfo))?;
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
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family"))
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
        ripdpi_runtime_platform::vpn::protect_socket(socket, Some(protect_path))
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

    use daemonize::Daemonize;
    use nix::fcntl::{Flock, FlockArg};

    use crate::model::config::ProcessSettings;

    pub struct ProcessGuard {
        _pid_file: Option<PidFileGuard>,
    }

    impl ProcessGuard {
        pub fn prepare(settings: ProcessSettings) -> io::Result<Self> {
            let pid_file_path = settings.pid_file_path;
            if settings.daemonize {
                daemonize(pid_file_path.as_deref())?;
            }
            let pid_file = match (settings.daemonize, pid_file_path) {
                (true, Some(path)) => Some(PidFileGuard::remove_on_drop(path)),
                (false, Some(path)) => Some(PidFileGuard::create(&path)?),
                (_, None) => None,
            };
            Ok(Self { _pid_file: pid_file })
        }
    }

    pub fn install_shutdown_signal_handlers(handler: extern "C" fn(std::os::raw::c_int)) -> io::Result<()> {
        ripdpi_runtime_platform::capability::install_shutdown_signal_handlers(handler)
    }

    pub fn detected_parallelism(fallback: usize) -> usize {
        ripdpi_runtime_platform::capability::detected_parallelism(fallback)
    }

    fn daemonize(pid_file: Option<&Path>) -> io::Result<()> {
        let daemon =
            pid_file.map_or_else(Daemonize::new, |path| Daemonize::new().pid_file(path)).working_directory("/");
        daemon.start().map_err(io::Error::other)
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

        fn remove_on_drop(path: PathBuf) -> Self {
            Self { file: None, path }
        }
    }

    impl Drop for PidFileGuard {
        fn drop(&mut self) {
            if let Some(file) = self.file.as_mut() {
                let _ = file.flush();
            }
            let _ = fs::remove_file(&self.path);
        }
    }
}
