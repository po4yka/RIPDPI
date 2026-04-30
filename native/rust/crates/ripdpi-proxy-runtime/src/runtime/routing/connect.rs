use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_config::{DesyncGroup, TcpChainStepKind};
use ripdpi_session::{S_ATP_I4, S_ATP_I6, S_AUTH_NONE, S_CMD_CONN, S_ER_GEN, S_VER5};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use ripdpi_runtime_platform as platform;

use super::super::state::RuntimeState;

#[derive(Debug)]
pub(in crate::runtime::routing) struct ConnectAttemptError {
    pub(in crate::runtime::routing) source: io::Error,
    pub(in crate::runtime::routing) tcp_total_retransmissions: Option<u32>,
    pub(in crate::runtime::routing) tcp_fast_open_enabled: bool,
}

impl ConnectAttemptError {
    pub(in crate::runtime::routing) fn into_io_error(self) -> io::Error {
        self.source
    }
}

pub(in crate::runtime::routing) fn connect_target_candidates_via_group(
    targets: &[SocketAddr],
    state: &RuntimeState,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let group = state.config.groups.get(group_index).ok_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: false,
    })?;
    let tfo_enabled = group_uses_tcp_fast_open(state, group, payload, allow_tfo);
    let mut last_error = None;
    for &candidate in targets {
        match connect_target_via_group_with_tfo(candidate, state, group_index, tfo_enabled) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::AddrNotAvailable, "no target candidates available"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo_enabled,
    }))
}

fn connect_target_via_group_with_tfo(
    target: SocketAddr,
    state: &RuntimeState,
    group_index: usize,
    tfo_enabled: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let started = std::time::Instant::now();
    let group = state.config.groups.get(group_index).ok_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: false,
    })?;
    let connect_timeout = if state.config.timeouts.connect_timeout_ms > 0 {
        Some(Duration::from_millis(state.config.timeouts.connect_timeout_ms as u64))
    } else {
        None
    };
    let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
        Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
        Some(_) => u32::MAX,
        None => w.window,
    });
    let stream = if let Some(upstream) = group.policy.ext_socks {
        connect_via_socks(
            target,
            upstream.addr,
            unspecified_ip_for(upstream.addr),
            state.config.process.protect_path.as_deref(),
            tfo_enabled,
            connect_timeout,
        )
        .map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo_enabled,
        })
    } else {
        connect_socket_detailed(
            target,
            unspecified_ip_for(target),
            state.config.process.protect_path.as_deref(),
            tfo_enabled,
            connect_timeout,
            pre_connect_rcvbuf,
        )
    }?;

    if group.actions.drop_sack {
        platform::attach_drop_sack(&stream).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo_enabled,
        })?;
    }
    // wsize supersedes window_clamp when both are set.
    let effective_clamp = group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp);
    if let Some(clamp) = effective_clamp {
        let _ = platform::set_tcp_window_clamp(&stream, clamp);
    }
    if group.actions.strip_timestamps {
        let _ = platform::attach_strip_timestamps(&stream);
    }
    let elapsed = started.elapsed().as_secs_f64();
    let group_label = format!("{group_index}");
    metrics::histogram!("ripdpi_connection_setup_duration_seconds", "group" => group_label).record(elapsed);
    if let Some(telemetry) = &state.telemetry {
        let upstream_addr = stream.peer_addr().unwrap_or(target);
        let upstream_rtt_ms = platform::tcp_round_trip_time_ms(&stream)
            .ok()
            .flatten()
            .or_else(|| Some(started.elapsed().as_millis() as u64));
        telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
    }
    Ok(stream)
}

fn group_has_syn_data(group: &DesyncGroup) -> bool {
    group.actions.tcp_chain.iter().any(|step| step.kind == TcpChainStepKind::SynData)
}

pub(in crate::runtime::routing) fn group_requests_direct_syn_data_tfo(
    group: &DesyncGroup,
    payload: Option<&[u8]>,
) -> bool {
    payload.is_some_and(|bytes| !bytes.is_empty()) && group.policy.ext_socks.is_none() && group_has_syn_data(group)
}

fn group_uses_tcp_fast_open(
    state: &RuntimeState,
    group: &DesyncGroup,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> bool {
    allow_tfo && (state.config.network.tfo || group_requests_direct_syn_data_tfo(group, payload))
}

fn unspecified_ip_for(addr: SocketAddr) -> IpAddr {
    match addr {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    }
}

fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    let mut stream = connect_socket(upstream, bind_ip, protect_path, tfo, connect_timeout)?;
    stream.set_read_timeout(connect_timeout)?;
    stream.set_write_timeout(connect_timeout)?;

    let handshake_result = (|| {
        stream.write_all(&[S_VER5, 1, S_AUTH_NONE])?;
        let mut auth = [0u8; 2];
        stream.read_exact(&mut auth)?;
        if auth != [S_VER5, S_AUTH_NONE] {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream socks auth failed"));
        }

        let request = encode_upstream_socks_connect(target);
        stream.write_all(&request)?;
        let reply = read_upstream_socks_reply(&mut stream)?;
        if reply.get(1).copied().unwrap_or(S_ER_GEN) != 0 {
            return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks connect failed"));
        }
        Ok(())
    })();

    handshake_result?;
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;
    Ok(stream)
}

pub(in crate::runtime) fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

pub(in crate::runtime) fn read_upstream_socks_reply(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

pub(in crate::runtime) fn connect_socket(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    connect_socket_detailed(target, bind_ip, protect_path, tfo, connect_timeout, None)
        .map_err(ConnectAttemptError::into_io_error)
}

fn connect_socket_detailed(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
    pre_connect_rcvbuf: Option<u32>,
) -> Result<TcpStream, ConnectAttemptError> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(|source| ConnectAttemptError {
        source,
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo,
    })?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, Some(path)).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo,
        })?;
    }
    if tfo {
        enable_tcp_fastopen_if_supported(&socket).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo,
        })?;
    }
    bind_socket(&socket, bind_ip, target).map_err(|source| ConnectAttemptError {
        source,
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo,
    })?;
    if let Some(rcvbuf) = pre_connect_rcvbuf {
        let _ = platform::set_rcvbuf(&socket, rcvbuf);
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
    if let Err(err) = connect_result {
        let tcp_total_retransmissions = platform::tcp_total_retransmissions(&socket).ok().flatten();
        tracing::warn!(
            target = %target,
            bind_ip = %bind_ip,
            tcp_fast_open = tfo,
            protected = protect_path.is_some(),
            elapsed_ms = connect_started.elapsed().as_millis() as u64,
            "ripdpi upstream connect failed: {err}"
        );
        return Err(ConnectAttemptError { source: err, tcp_total_retransmissions, tcp_fast_open_enabled: tfo });
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

fn enable_tcp_fastopen_if_supported(socket: &Socket) -> io::Result<()> {
    match platform::enable_tcp_fastopen_connect(socket) {
        Ok(()) => Ok(()),
        #[cfg(target_os = "android")]
        Err(err) if should_ignore_android_tfo_error(&err) => {
            tracing::debug!("TCP Fast Open unavailable on this Android build: {err}");
            Ok(())
        }
        Err(err) => Err(err),
    }
}

#[cfg(any(test, target_os = "android"))]
fn should_ignore_android_tfo_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES | libc::EINVAL))
}

fn bind_socket(socket: &Socket, bind_ip: IpAddr, target: SocketAddr) -> io::Result<()> {
    if is_unspecified(bind_ip) {
        return Ok(());
    }
    let bind_addr = match (bind_ip, target) {
        (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
        (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family")),
    };
    socket.bind(&SockAddr::from(bind_addr))
}

fn is_unspecified(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_unspecified(),
        IpAddr::V6(ip) => ip.is_unspecified(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{OffsetExpr, TcpChainStep, UpstreamSocksConfig};
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;
    use std::time::Instant;

    #[test]
    fn outbound_connects_do_not_reuse_listener_bind_ip() {
        assert_eq!(unspecified_ip_for(SocketAddr::from(([203, 0, 113, 7], 443))), IpAddr::V4(Ipv4Addr::UNSPECIFIED),);
        assert_eq!(
            unspecified_ip_for(SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443))),
            IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        );
    }

    #[test]
    fn android_tfo_capability_errors_are_ignored() {
        for errno in [libc::ENOPROTOOPT, libc::EOPNOTSUPP, libc::EPERM, libc::EACCES, libc::EINVAL] {
            assert!(
                should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to be ignored on Android",
            );
        }
    }

    #[test]
    fn android_tfo_runtime_failures_are_not_ignored() {
        for errno in [libc::ECONNRESET, libc::ETIMEDOUT, libc::EHOSTUNREACH] {
            assert!(
                !should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to remain fatal on Android",
            );
        }
    }

    #[test]
    fn direct_syn_data_tfo_requires_payload_and_direct_upstream() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));

        assert!(group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
        assert!(!group_requests_direct_syn_data_tfo(&group, None));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(&[])));

        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::from(([127, 0, 0, 1], 1080)) });
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
    }

    #[test]
    fn upstream_socks_auth_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept upstream socks client");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("auth stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_reply_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("connect reply stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_clears_temporary_timeouts_after_success() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            stream.write_all(&[S_VER5, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0x1f, 0x90]).expect("write connect success");
        });

        let stream = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect("connect via upstream socks");

        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
        server.join().expect("join upstream socks server");
    }
}
