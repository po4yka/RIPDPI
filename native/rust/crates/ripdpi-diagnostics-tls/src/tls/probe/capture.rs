use std::net::TcpStream;
use std::sync::Arc;
use std::time::Instant;

use ripdpi_tls_profiles::{TlsTemplateFirstFlightPlan, plan_first_flight};
use rustls::client::danger::ServerCertVerifier;
use rustls::{ClientConnection, StreamOwned};

use crate::ja3::{self, RecordingStream};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig};

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

pub(crate) fn capture_tls_handshake(
    socket: TcpStream,
    targets: &[TargetAddress],
    transport: &TransportConfig,
    tls_name: &str,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
) -> Result<CapturedTlsHandshake, String> {
    let target = targets.first().ok_or_else(|| "no_tls_targets".to_string())?;
    let template_profile = planned_tls_template_profile(profile);
    let config = match profile {
        TlsClientProfile::Tls13WithEch => {
            build_ech_client_config(tls_name, target, transport, tls_verifier, key_log, application_protocol)?
        }
        _ => match key_log {
            Some(key_log) => {
                build_standard_client_config_with_key_log(profile, tls_verifier, Some(key_log), application_protocol)
            }
            None => build_standard_client_config_with_key_log(profile, tls_verifier, None, application_protocol),
        },
    };
    let server_name = make_server_name(tls_name, target)?;
    let mut connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;
    let mut recording = RecordingStream::new(socket);

    let tls_start = Instant::now();
    while connection.is_handshaking() {
        connection.complete_io(&mut recording).map_err(|err| err.to_string())?;
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
