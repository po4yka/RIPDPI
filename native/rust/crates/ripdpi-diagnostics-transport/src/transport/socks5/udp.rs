use super::*;
use ripdpi_socks5_core::udp_feedback::{self, Frame, Nonce};
use std::io;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use crate::transport::udp::{
    cancellation_reason, matched_udp_result, poll_wait, send_udp_before, udp_exchange_deadline, udp_remaining,
};
use crate::transport::watchdog::run_with_socket_abort_watchdog;
use crate::util::with_scan_io_deadline;

pub fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
    credentials: Option<&Socks5Credentials>,
) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    relay_udp_via_socks5_matching(proxy_host, proxy_port, destination, payload, credentials, &|_| true, None)
}

pub(crate) fn relay_udp_via_socks5_matching(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
    credentials: Option<&Socks5Credentials>,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    let deadline = udp_exchange_deadline(cancel)?;
    let mut control = connect_control(proxy_host, proxy_port, credentials, deadline, cancel)?;
    let mut prebound = None;
    let mut nonce = None;
    let relay = if credentials.is_some() && control.peer_addr()?.ip().is_loopback() {
        let udp = UdpSocket::bind(SocketAddr::new(control.local_addr()?.ip(), 0))?;
        let source = udp.local_addr()?;
        match guard_control(&mut control, deadline, cancel, |control| private_associate(control, source, deadline))? {
            Some((relay, session_nonce)) => {
                prebound = Some(udp);
                nonce = Some(session_nonce);
                relay
            }
            None => {
                // An unsupported private request cannot leave a reusable control stream.
                drop(control);
                control = connect_control(proxy_host, proxy_port, credentials, deadline, cancel)?;
                standard_association(&mut control, deadline, cancel)?
            }
        }
    } else {
        standard_association(&mut control, deadline, cancel)?
    };
    let udp = match prebound {
        Some(udp) => udp,
        None => UdpSocket::bind(SocketAddr::new(
            if relay.is_ipv4() { Ipv4Addr::UNSPECIFIED.into() } else { std::net::Ipv6Addr::UNSPECIFIED.into() },
            0,
        ))?,
    };
    udp.connect(relay)?;
    let frame = encode_socks5_udp_frame(destination, payload);
    let frame = match nonce {
        Some(nonce) => udp_feedback::encode_request(&nonce, 1, &frame)?,
        None => frame,
    };
    if nonce.is_some() {
        control.set_nonblocking(true)?;
        check_control(&control)?;
    }
    send_udp_before(&udp, &frame, deadline, cancel)?;
    let mut bytes = [0; 65_535];
    let mut saw_unmatched = false;
    loop {
        let timeout = matched_udp_result(udp_remaining(deadline, cancel), saw_unmatched)?;
        if nonce.is_some() {
            check_control(&control)?;
        }
        udp.set_read_timeout(Some(poll_wait(timeout, nonce.is_some() || cancel.is_some())))?;
        let size = match udp.recv(&mut bytes) {
            Ok(size) => size,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                ) =>
            {
                continue;
            }
            Err(error) => return Err(TransportError::udp_recv_error(error)),
        };
        matched_udp_result(udp_remaining(deadline, cancel), saw_unmatched)?;
        let body = match nonce.as_ref().map(|nonce| udp_feedback::decode(&bytes[..size], nonce)) {
            Some(Ok(Frame::Response { payload })) => {
                check_control(&control)?;
                payload
            }
            Some(Ok(Frame::Error { id: 1, error })) if error.target == destination => {
                check_control(&control)?;
                return Err(
                    io::Error::new(io::ErrorKind::ConnectionRefused, "SOCKS5 UDP peer reported an error").into()
                );
            }
            Some(_) => continue,
            None => &bytes[..size],
        };
        match decode_socks5_udp_frame(body) {
            Ok((source, payload)) if source == destination => {
                if accept_response(&payload) {
                    return Ok((payload, udp.local_addr()?));
                }
                saw_unmatched = true;
                continue;
            }
            _ if nonce.is_some() => continue,
            Ok(_) => {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP source mismatch").into());
            }
            Err(error) => return Err(error),
        }
    }
}

fn connect_control(
    host: &str,
    port: u16,
    credentials: Option<&Socks5Credentials>,
    deadline: Instant,
    cancel: Option<&AtomicBool>,
) -> Result<TcpStream, TransportError> {
    control_remaining(deadline, cancel)?;
    let check_abort = || cancellation_reason(cancel);
    let result = with_scan_io_deadline(Some(deadline), || {
        connect_direct_observed(&[TargetAddress::Host(host.to_string())], port, None, Some(&check_abort))
    });
    control_remaining(deadline, cancel)?;
    let mut control =
        result.map_err(|error| TransportError::ConnectFailed { stage: error.stage, message: error.message })?.stream;
    guard_control(&mut control, deadline, cancel, |control| socks5_handshake(control, credentials))?;
    Ok(control)
}

fn standard_association(
    control: &mut TcpStream,
    deadline: Instant,
    cancel: Option<&AtomicBool>,
) -> Result<SocketAddr, TransportError> {
    let check_abort = || cancellation_reason(cancel);
    let relay = guard_control(control, deadline, cancel, |control| {
        socks5_udp_associate_with_abort(control, Some(&check_abort))
    })?;
    normalize_udp_relay_addr(relay, control)
}

fn guard_control<T>(
    control: &mut TcpStream,
    deadline: Instant,
    cancel: Option<&AtomicBool>,
    operation: impl FnOnce(&mut TcpStream) -> Result<T, TransportError>,
) -> Result<T, TransportError> {
    let wait = poll_wait(control_remaining(deadline, cancel)?, false);
    control.set_read_timeout(Some(wait))?;
    control.set_write_timeout(Some(wait))?;
    let clone = control.try_clone()?;
    let result = with_scan_io_deadline(Some(deadline), || {
        run_with_socket_abort_watchdog(clone, &|| cancellation_reason(cancel).is_some(), || operation(control))
    });
    control_remaining(deadline, cancel)?;
    result
}

fn control_remaining(deadline: Instant, cancel: Option<&AtomicBool>) -> Result<Duration, TransportError> {
    udp_remaining(deadline, cancel).map_err(|error| match error {
        TransportError::UdpRecvTimeout => TransportError::SocksNegotiationTimedOut,
        other => other,
    })
}

fn private_associate(
    control: &mut TcpStream,
    source: SocketAddr,
    deadline: Instant,
) -> Result<Option<(SocketAddr, Nonce)>, TransportError> {
    let mut request = encode_socks5_udp_frame(source, &[]);
    request[..3].copy_from_slice(&[5, udp_feedback::COMMAND, 0]);
    control.set_write_timeout(Some(remaining(deadline)?))?;
    control.write_all(&request)?;
    let mut header = [0; 4];
    read_until(control, &mut header, deadline)?;
    if header[0] != 5 || header[2] != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 reply header").into());
    }
    if header[1] != 0 && header[1] != 7 {
        return Err(TransportError::SocksUdpAssociateFailed(header[1]));
    }
    let peer = control.peer_addr()?.ip();
    let address = match header[3] {
        1 => {
            let mut address = [0; 4];
            read_until(control, &mut address, deadline)?;
            IpAddr::from(address)
        }
        4 => {
            let mut address = [0; 16];
            read_until(control, &mut address, deadline)?;
            IpAddr::from(address)
        }
        3 => {
            let mut length = [0];
            read_until(control, &mut length, deadline)?;
            let mut address = vec![0; usize::from(length[0])];
            read_until(control, &mut address, deadline)?;
            let name = std::str::from_utf8(&address)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid private SOCKS5 relay host"))?;
            if name.eq_ignore_ascii_case("localhost") || name.eq_ignore_ascii_case("localhost.") {
                peer
            } else {
                name.parse().map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "private SOCKS5 relay requires a numeric host")
                })?
            }
        }
        atyp => return Err(TransportError::SocksUdpAssociateAtypUnsupported(atyp)),
    };
    let mut port = [0; 2];
    read_until(control, &mut port, deadline)?;
    if header[1] == 7 {
        return Ok(None);
    }
    let address = if address.is_unspecified() { peer } else { address };
    let port = u16::from_be_bytes(port);
    if !address.is_loopback() || port == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid private SOCKS5 relay endpoint").into());
    }
    let mut preface = [0; udp_feedback::PREFACE_LEN];
    read_until(control, &mut preface, deadline)?;
    Ok(Some((SocketAddr::new(address, port), udp_feedback::decode_preface(&preface)?)))
}

fn read_until(control: &mut TcpStream, mut bytes: &mut [u8], deadline: Instant) -> io::Result<()> {
    while !bytes.is_empty() {
        control.set_read_timeout(Some(remaining(deadline)?))?;
        match control.read(bytes) {
            Ok(0) => {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "SOCKS5 control closed"));
            }
            Ok(size) => bytes = &mut bytes[size..],
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

fn check_control(control: &TcpStream) -> io::Result<()> {
    match control.peek(&mut [0]) {
        Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(()),
        Err(error) => Err(error),
        Ok(_) => Err(io::Error::new(io::ErrorKind::ConnectionAborted, "SOCKS5 UDP control is not idle")),
    }
}

fn remaining(deadline: Instant) -> io::Result<Duration> {
    deadline
        .checked_duration_since(Instant::now())
        .filter(|duration| !duration.is_zero())
        .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "SOCKS5 UDP operation timed out"))
}
