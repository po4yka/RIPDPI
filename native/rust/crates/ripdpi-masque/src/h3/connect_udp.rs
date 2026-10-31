use std::future::poll_fn;
use std::io;

use bytes::{Buf, Bytes};
use h3::error::Code;
use h3::ext::Protocol;
use h3_datagram::datagram_handler::HandleDatagramsExt;
use http::Request;
use tokio::sync::mpsc;

use super::datagram::decode_udp_payload;
use super::transport::connect_h3_transport;
use crate::auth::AuthHeader;
use crate::capsule::CapsuleReader;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{AttemptError, validate_connect_udp_response};
use crate::udp::{MasqueUdpFlow, MasqueUdpSender};
use crate::url::{build_connect_udp_path, parse_proxy_origin, parse_target};

/// Opens an H3 CONNECT-UDP tunnel to `target` and spawns its reader, which
/// forwards HTTP Datagrams from QUIC DATAGRAM frames and DATAGRAM capsules alike.
///
/// # Cancel safety
///
/// cancel-safe: the tasks are spawned after the last await, so cancellation
/// before that drops the connection and request stream and leaves nothing running.
pub(crate) async fn attempt_h3_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = parse_target(target)?;
    let proxy_origin = parse_proxy_origin(config)?;
    let (mut driver, mut send_request, path_connection) = connect_h3_transport(config, true).await?;

    let request_uri = format!("https://{}{}", proxy_origin.authority, build_connect_udp_path(&proxy_origin, &target));
    let request = Request::builder().method("CONNECT").uri(request_uri).header("capsule-protocol", "?1");
    let mut request = apply_request_headers(request, config, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-UDP request: {error}"))
    })?;
    request.extensions_mut().insert(Protocol::CONNECT_UDP);

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-UDP request: {error}"))
    })?;
    // No FIN: the request stream carries the tunnel's lifetime (RFC 9114 4.4,
    // RFC 9298 3.1), and proxies such as masque-go close the UDP socket on
    // EOF. The reader task owns the stream until the flow is dropped.
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-UDP response: {error}"))
    })?;
    validate_connect_udp_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let stream_id = stream.id();
    let datagram_stream_id_len = quic_varint_len(stream_id.index());
    let mut datagram_reader = driver.get_datagram_reader();
    let target_label = target.authority();

    // The task ends when the proxy closes the request stream (RFC 9298 3.1:
    // it does so when the UDP socket is gone), which retires the flow so the
    // next send opens a new tunnel. Both reads are poll_fn-based and a partial
    // capsule stays in `capsules`, so the select loses nothing when the other
    // branch wins.
    let reader_task = tokio::spawn(async move {
        let mut stream = stream;
        let mut capsules = CapsuleReader::default();
        'flow: loop {
            // HTTP Datagrams arrive in QUIC DATAGRAM frames or, if the proxy
            // chooses, as DATAGRAM capsules on the request stream (RFC 9297 3.5).
            let (payloads, from_capsules) = tokio::select! {
                datagram = datagram_reader.read_datagram() => match datagram {
                    Ok(datagram) if datagram.stream_id() == stream_id => (vec![datagram.into_payload()], false),
                    Ok(_) => continue,
                    Err(error) => {
                        tracing::debug!(error = %error, target = %target_label, "MASQUE UDP datagram reader closed");
                        break;
                    }
                },
                data = stream.recv_data() => match data {
                    Ok(Some(mut data)) => {
                        let chunk = data.copy_to_bytes(data.remaining());
                        (capsules.push(&chunk).into_iter().map(Bytes::from).collect(), true)
                    }
                    Ok(None) | Err(_) => {
                        tracing::debug!(target = %target_label, "MASQUE CONNECT-UDP request stream closed by the proxy");
                        break;
                    }
                },
            };
            for payload in payloads {
                match decode_udp_payload(payload) {
                    Ok(Some(payload)) => {
                        if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                            break 'flow;
                        }
                    }
                    Ok(None) => {
                        tracing::debug!(target = %target_label, "ignored MASQUE UDP datagram for unsupported context id");
                    }
                    // A malformed capsule is a stream error (RFC 9297 3.3); ending the task retires the flow.
                    Err(error) if from_capsules => {
                        tracing::debug!(error = %error, target = %target_label, "aborting MASQUE UDP flow on a malformed capsule");
                        // Reset only our side: h3-quinn 0.0.10 panics on stop_sending while h3's
                        // read-ahead holds the receive stream; dropping it stops the receive side.
                        stream.stop_stream(Code::H3_DATAGRAM_ERROR);
                        break 'flow;
                    }
                    Err(error) => {
                        tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE UDP datagram");
                    }
                }
            }
        }
    });

    let driver_task = tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 UDP driver closed");
    });

    Ok(MasqueUdpFlow::new(
        MasqueUdpSender::H3 { connection: path_connection.clone(), stream_id },
        Some(send_request),
        driver_task,
        reader_task,
        Some(path_connection),
        datagram_stream_id_len,
    ))
}

fn quic_varint_len(value: u64) -> usize {
    match value {
        0..=63 => 1,
        64..=16_383 => 2,
        16_384..=1_073_741_823 => 4,
        _ => 8,
    }
}
