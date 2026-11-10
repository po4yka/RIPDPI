use std::io::{self, Read, Write};
use std::net::{IpAddr, Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use super::{HttpConnectFailure, HttpConnectHead, RuntimeState, SuccessReply};

/// Return only domain CONNECT requests. The normal parser still owns IP/UDP requests.
pub(super) fn target(request: &[u8]) -> io::Result<Option<(String, u16)>> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidInput, "invalid remote DNS authority");
    let (host, port) = match request.first() {
        Some(5) if request.get(3) == Some(&3) => {
            if request.get(1) != Some(&1) || request.get(2) != Some(&0) {
                return Err(invalid());
            }
            let len = usize::from(*request.get(4).ok_or_else(invalid)?);
            let host = std::str::from_utf8(request.get(5..5 + len).ok_or_else(invalid)?).map_err(|_| invalid())?;
            let port = request.get(5 + len..7 + len).ok_or_else(invalid)?;
            (host, u16::from_be_bytes([port[0], port[1]]))
        }
        Some(4) if request.get(4..7) == Some(&[0, 0, 0][..]) && request.get(7).is_some_and(|b| *b != 0) => {
            if request.get(1) != Some(&1) {
                return Err(invalid());
            }
            let id_end = request.get(8..).ok_or_else(invalid)?.iter().position(|b| *b == 0).ok_or_else(invalid)? + 8;
            let bytes = request.get(id_end + 1..).ok_or_else(invalid)?;
            let len = bytes.iter().position(|b| *b == 0).ok_or_else(invalid)?;
            let host = std::str::from_utf8(&bytes[..len]).map_err(|_| invalid())?;
            (host, u16::from_be_bytes([request[2], request[3]]))
        }
        Some(b'C') => HttpConnectHead::parse(request).and_then(HttpConnectHead::authority).map_err(|_| invalid())?,
        _ => return Ok(None),
    };
    if host.parse::<IpAddr>().is_ok() {
        return Ok(None);
    }
    if port == 0
        || host.is_empty()
        || host.len() > 255
        || !host.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
    {
        return Err(invalid());
    }
    Ok(Some((host.to_owned(), port)))
}

pub(super) fn relay(
    client: &mut TcpStream,
    state: &RuntimeState,
    request: &[u8],
    reply: SuccessReply,
) -> io::Result<bool> {
    let Some(upstream) = state.remote_dns_upstream() else {
        return Ok(false);
    };
    let connected = (|| {
        let Some((host, port)) = target(request)? else {
            return Ok(None);
        };
        state.check_remote_domain_policy(&host, port)?;
        super::super::routing::connect_remote_domain(&host, port, upstream, state).map(Some)
    })();
    let mut remote = match connected {
        Ok(Some(remote)) => remote,
        Ok(None) => return Ok(false),
        Err(error) => {
            match reply {
                SuccessReply::Socks4 => client.write_all(RuntimeState::encode_socks4_reply(false).as_bytes())?,
                SuccessReply::Socks5 => {
                    let code = if error.kind() == io::ErrorKind::PermissionDenied {
                        2
                    } else {
                        RuntimeState::socks5_reply_code_for_error(&error)
                    };
                    client.write_all(&[5, code, 0, 1, 0, 0, 0, 0, 0, 0])?;
                }
                SuccessReply::HttpConnect => {
                    let failure = match error.kind() {
                        io::ErrorKind::PermissionDenied => HttpConnectFailure::Forbidden,
                        io::ErrorKind::InvalidInput => HttpConnectFailure::BadRequest,
                        kind => HttpConnectFailure::for_connect_error(kind),
                    };
                    client.write_all(RuntimeState::encode_http_connect_failure(failure).as_bytes())?;
                }
                _ => {}
            }
            return Err(error);
        }
    };
    let _active = state.register_active_upstream_tcp_socket(&remote)?;
    super::connect_relay::write_remote_success_reply(client, &reply, &remote)?;
    client.set_read_timeout(Some(Duration::from_millis(100)))?;
    remote.set_read_timeout(Some(Duration::from_millis(100)))?;
    client.set_write_timeout(Some(Duration::from_millis(100)))?;
    remote.set_write_timeout(Some(Duration::from_millis(100)))?;
    let mut client_writer = client.try_clone()?;
    let mut remote_reader = remote.try_clone()?;
    // These atomics carry only cancellation and a timestamp; they publish no other data.
    let stop = AtomicBool::new(false);
    let activity = AtomicU64::new(0);
    let started = Instant::now();
    std::thread::scope(|scope| {
        let down = std::thread::Builder::new()
            .name("ripdpi-tor-stream".into())
            .spawn_scoped(scope, || copy(&mut remote_reader, &mut client_writer, state, &stop, &activity, started))?;
        let up = copy(client, &mut remote, state, &stop, &activity, started);
        let down = down.join().map_err(|_| io::Error::other("remote DNS relay worker panicked"))?;
        up.and(down)
    })?;
    Ok(true)
}

fn copy(
    reader: &mut TcpStream,
    writer: &mut TcpStream,
    state: &RuntimeState,
    stop: &AtomicBool,
    activity: &AtomicU64,
    started: Instant,
) -> io::Result<()> {
    let result = (|| {
        let mut bytes = [0; 16384];
        loop {
            if state.shutdown_requested() || stop.load(Ordering::Relaxed) {
                return Ok(());
            }
            let now = started.elapsed().as_millis() as u64;
            if now.saturating_sub(activity.load(Ordering::Relaxed)) > 300_000 {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "Tor stream idle timeout"));
            }
            let len = match reader.read(&mut bytes) {
                Ok(0) => return Ok(()),
                Ok(len) => len,
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => continue,
                Err(e) => return Err(e),
            };
            activity.fetch_max(now, Ordering::Relaxed);
            let mut tail = &bytes[..len];
            while !tail.is_empty() {
                if state.shutdown_requested() || stop.load(Ordering::Relaxed) {
                    return Ok(());
                }
                if (started.elapsed().as_millis() as u64).saturating_sub(activity.load(Ordering::Relaxed)) > 300_000 {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "Tor stream idle timeout"));
                }
                match writer.write(tail) {
                    Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
                    Ok(n) => {
                        tail = &tail[n..];
                        activity.fetch_max(started.elapsed().as_millis() as u64, Ordering::Relaxed);
                    }
                    Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(e) => return Err(e),
                }
            }
        }
    })();
    let _ = writer.shutdown(Shutdown::Write);
    if result.is_err() {
        stop.store(true, Ordering::Relaxed);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_malformed_remote_authorities_and_non_connect_commands() {
        for request in [
            vec![5, 1, 0, 3, 0, 1, 187],
            vec![5, 1, 0, 3, 3, b'a'],
            vec![5, 2, 0, 3, 1, b'a', 1, 187],
            vec![5, 1, 0, 3, 3, b'a', 0, b'b', 1, 187],
            vec![5, 1, 0, 3, 1, b'a', 0, 0],
        ] {
            assert!(target(&request).is_err());
        }
        assert_eq!(target(&[5, 1, 0, 1, 192, 0, 2, 1, 1, 187]).unwrap(), None);
        assert_eq!(
            target(b"CONNECT a.onion:443 HTTP/1.1\r\nHost: different.onion:443\r\n\r\n").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }
}
