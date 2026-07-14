use std::future::poll_fn;
use std::io;

use h3::ext::Protocol;
use h3_datagram::datagram_handler::HandleDatagramsExt;
use http::Request;
use tokio::sync::mpsc;

use super::datagram::decode_udp_payload;
use super::transport::connect_h3_transport;
use crate::auth::AuthHeader;
use crate::config::MasqueConfig;
use crate::request::apply_request_headers;
use crate::response::{AttemptError, validate_connect_udp_response};
use crate::udp::{MasqueUdpFlow, MasqueUdpSender};
use crate::url::{build_connect_udp_path, parse_proxy_origin, parse_target};

pub(crate) async fn attempt_h3_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = parse_target(target)?;
    let proxy_origin = parse_proxy_origin(config)?;
    let (mut driver, mut send_request) = connect_h3_transport(config, true).await?;

    let request_uri = format!("https://{}{}", proxy_origin.authority, build_connect_udp_path(&proxy_origin, &target));
    let request = Request::builder().method("CONNECT").uri(request_uri).header("capsule-protocol", "?1");
    let mut request = apply_request_headers(request, config, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-UDP request: {error}"))
    })?;
    request.extensions_mut().insert(Protocol::CONNECT_UDP);

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-UDP request: {error}"))
    })?;
    stream.finish().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to finish H3 CONNECT-UDP request: {error}"))
    })?;
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-UDP response: {error}"))
    })?;
    validate_connect_udp_response(response.status(), response.headers(), config.effective_auth_mode())?;

    let stream_id = stream.id();
    let datagram_sender = driver.get_datagram_sender(stream_id);
    let mut datagram_reader = driver.get_datagram_reader();
    let target_label = target.authority();

    let reader_task = tokio::spawn(async move {
        let _stream = stream;
        loop {
            let datagram = match datagram_reader.read_datagram().await {
                Ok(datagram) => datagram,
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "MASQUE UDP datagram reader closed");
                    break;
                }
            };
            if datagram.stream_id() != stream_id {
                continue;
            }
            let payload = datagram.into_payload();
            match decode_udp_payload(payload) {
                Ok(Some(payload)) => {
                    if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                        break;
                    }
                }
                Ok(None) => {
                    tracing::debug!(target = %target_label, "ignored MASQUE UDP datagram for unsupported context id");
                }
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE UDP datagram");
                }
            }
        }
    });

    let driver_task = tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 UDP driver closed");
    });

    Ok(MasqueUdpFlow::new(MasqueUdpSender::H3(datagram_sender), driver_task, reader_task))
}
