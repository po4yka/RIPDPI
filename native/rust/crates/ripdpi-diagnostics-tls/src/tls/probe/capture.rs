use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Instant;

use ripdpi_tls_profiles::{TlsTemplateFirstFlightPlan, plan_first_flight};
use rustls::client::danger::ServerCertVerifier;
use rustls::{ClientConnection, StreamOwned};

use crate::ja3::{self, RecordingStream};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::super::certs::extract_cert_info;
use super::super::config::{
    build_ech_client_config, build_standard_client_config_with_key_log, make_server_name, planned_tls_template_profile,
};
use super::super::key_log::TlsKeyLogCallback;
use super::super::types::{ApplicationProtocolPolicy, TlsClientProfile};

pub(crate) struct CapturedTlsHandshake {
    pub(crate) stream: ConnectionStream,
    pub(crate) tls_template_first_flight_plan: Option<TlsTemplateFirstFlightPlan>,
    pub(crate) tls_handshake_ms: u64,
    pub(crate) negotiated_alpn: Option<String>,
    pub(crate) cert_chain_length: Option<usize>,
    pub(crate) cert_issuer: Option<String>,
    pub(crate) ja3_fingerprint: Option<String>,
}

pub(crate) struct CaptureTlsHandshakeOptions<'a> {
    pub(crate) targets: &'a [TargetAddress],
    pub(crate) transport: &'a TransportConfig,
    pub(crate) tls_name: &'a str,
    pub(crate) profile: TlsClientProfile,
    pub(crate) tls_verifier: Option<&'a Arc<dyn ServerCertVerifier>>,
    pub(crate) key_log: Option<&'a TlsKeyLogCallback>,
    pub(crate) application_protocol: ApplicationProtocolPolicy,
    pub(crate) should_abort: &'a dyn Fn() -> Option<&'static str>,
}

pub(crate) fn capture_tls_handshake(
    socket: TcpStream,
    options: &CaptureTlsHandshakeOptions<'_>,
) -> Result<CapturedTlsHandshake, String> {
    let should_abort = options.should_abort;
    if let Some(reason) = should_abort() {
        return Err(format!("probe_aborted:{reason}"));
    }
    let target = options.targets.first().ok_or_else(|| "no_tls_targets".to_string())?;
    let template_profile = planned_tls_template_profile(options.profile);
    let config = match options.profile {
        TlsClientProfile::Tls13WithEch => build_ech_client_config(
            options.tls_name,
            target,
            options.transport,
            options.tls_verifier,
            options.key_log,
            options.application_protocol,
        )?,
        _ => match options.key_log {
            Some(key_log) => build_standard_client_config_with_key_log(
                options.profile,
                options.tls_verifier,
                Some(key_log),
                options.application_protocol,
            ),
            None => build_standard_client_config_with_key_log(
                options.profile,
                options.tls_verifier,
                None,
                options.application_protocol,
            ),
        },
    };
    let server_name = make_server_name(options.tls_name, target)?;
    let mut connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;
    let mut recording = RecordingStream::new(socket);

    let tls_start = Instant::now();
    while connection.is_handshaking() {
        if let Some(reason) = should_abort() {
            return Err(format!("probe_aborted:{reason}"));
        }
        refresh_tls_handshake_timeout(&mut recording)?;
        let mut io = BoundedHandshakeIo { recording: &mut recording, should_abort };
        if let Err(err) = connection.complete_io(&mut io) {
            if let Some(admin) = administrative_error_after_io(should_abort) {
                return Err(admin);
            }
            return Err(err.to_string());
        }
    }
    let tls_handshake_ms = tls_start.elapsed().as_millis() as u64;

    let ja3_fingerprint = ja3::compute_ja3(recording.recorded_writes());
    let tls_template_first_flight_plan = plan_first_flight(template_profile, recording.recorded_writes());
    let (socket, _recorded) = recording.into_parts();
    let tls_stream = StreamOwned::new(connection, socket);
    let negotiated_alpn =
        tls_stream.conn.alpn_protocol().map(|protocol| String::from_utf8_lossy(protocol).into_owned());
    let (cert_chain_length, cert_issuer) = extract_cert_info(&tls_stream.conn);

    Ok(CapturedTlsHandshake {
        stream: ConnectionStream::Tls(Box::new(tls_stream)),
        tls_template_first_flight_plan,
        tls_handshake_ms,
        negotiated_alpn,
        cert_chain_length,
        cert_issuer,
        ja3_fingerprint,
    })
}

fn administrative_error_after_io(should_abort: &dyn Fn() -> Option<&'static str>) -> Option<String> {
    if let Some(reason) = should_abort() {
        Some(format!("probe_aborted:{reason}"))
    } else if bounded_scan_io_timeout(IO_TIMEOUT).is_err() {
        Some("scan_deadline_exceeded".to_string())
    } else {
        None
    }
}

/// Handshake socket I/O that checks cancellation and the scan deadline before every read and
/// write. `complete_io` loops until the handshake ends, and a socket timeout only bounds one
/// read, so a peer that sends a few bytes within every timeout would otherwise hold the probe
/// past the deadline and past a user cancel.
struct BoundedHandshakeIo<'a> {
    recording: &'a mut RecordingStream<TcpStream>,
    should_abort: &'a dyn Fn() -> Option<&'static str>,
}

impl BoundedHandshakeIo<'_> {
    fn before_io(&mut self) -> io::Result<()> {
        // Not `Interrupted`: rustls retries an interrupted read instead of returning.
        if let Some(reason) = (self.should_abort)() {
            return Err(io::Error::other(format!("probe_aborted:{reason}")));
        }
        refresh_tls_handshake_timeout(self.recording).map_err(|err| io::Error::new(io::ErrorKind::TimedOut, err))
    }
}

impl Read for BoundedHandshakeIo<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.before_io()?;
        self.recording.read(buf)
    }
}

impl Write for BoundedHandshakeIo<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.before_io()?;
        self.recording.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.recording.flush()
    }
}

fn refresh_tls_handshake_timeout(recording: &mut RecordingStream<TcpStream>) -> Result<(), String> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    let socket = recording.inner_mut();
    socket.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use crate::tls::{TlsClientProfile, try_tls_handshake};
    use crate::transport::{TargetAddress, direct_transport};

    /// Accepts one client, reads its ClientHello, then sends a TLS record header and one byte
    /// of the record every 100 ms for `drip_for`: each read returns well within the socket
    /// timeout, but the handshake never completes.
    fn slow_drip_server(drip_for: Duration) -> (u16, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut hello = [0u8; 4096];
            let _ = socket.read(&mut hello);
            if socket.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]).is_err() {
                return;
            }
            let started = Instant::now();
            while started.elapsed() < drip_for {
                std::thread::sleep(Duration::from_millis(100));
                if socket.write_all(&[0x00]).is_err() {
                    return;
                }
            }
        });
        (port, server)
    }

    #[test]
    fn slow_drip_handshake_stops_at_the_scan_deadline() {
        let (port, server) = slow_drip_server(Duration::from_secs(4));
        let started = Instant::now();

        let observation = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(500)), || {
            try_tls_handshake(
                &TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST)),
                port,
                &direct_transport(),
                "localhost",
                true,
                TlsClientProfile::Tls13Only,
                None,
            )
        });

        let elapsed = started.elapsed();
        assert_eq!(observation.status, "tls_handshake_failed");
        assert_eq!(observation.error.as_deref(), Some("scan_deadline_exceeded"));
        assert!(elapsed < Duration::from_secs(2), "handshake ran for {elapsed:?}");
        server.join().unwrap();
    }
}
