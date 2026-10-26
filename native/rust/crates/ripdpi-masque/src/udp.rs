use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::{Buf, Bytes};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::capsule::encode_connect_udp_payload;
use crate::client::MasqueClientInner;
use crate::h2::attempt_h2_connect_udp;
use crate::h3::attempt_h3_connect_udp;
use crate::response::{bounded_attempt, classify_attempt_failure};

type H3RequestSender = h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>;

const MAX_UDP_FLOWS: usize = 64;
const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(120);
const H3_DATAGRAM_TOO_LARGE: &str = "masque_h3_datagram_too_large: payload exceeds the current QUIC path limit";

fn h3_datagram_send_error(error: quinn::SendDatagramError) -> io::Error {
    match error {
        quinn::SendDatagramError::TooLarge => io::Error::new(io::ErrorKind::InvalidInput, H3_DATAGRAM_TOO_LARGE),
        other => io::Error::other(format!("failed to send MASQUE UDP datagram: {other}")),
    }
}

pub(crate) enum MasqueUdpSender {
    H3 { connection: quinn::Connection, stream_id: h3::quic::StreamId },
    H2(mpsc::Sender<Vec<u8>>),
}

pub struct MasqueUdpRelay {
    pub(crate) client: Arc<MasqueClientInner>,
    pub(crate) flows: HashMap<String, MasqueUdpFlow>,
    pub(crate) incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
    pub(crate) incoming_rx: mpsc::Receiver<(String, Vec<u8>)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MasqueQuicPathSnapshot {
    pub current_mtu: u16,
    pub max_datagram_size: Option<usize>,
    pub sent_datagram_frames: u64,
    pub received_datagram_frames: u64,
    pub lost_packets: u64,
    pub sent_plpmtud_probes: u64,
    pub lost_plpmtud_probes: u64,
    pub black_holes_detected: u64,
}

/// Drop order: the `Drop::drop` body aborts both `driver_task` and
/// `reader_task` (tokio `.abort()` is non-blocking and merely flips
/// the task's cancellation flag) before any field drops. After the
/// body returns, the fields drop in declaration order: `sender`, the retained
/// H3 request sender that keeps the connection open, then the two `JoinHandle`s. Dropping
/// a `JoinHandle` without `await` is a detach -- the tokio runtime
/// reclaims the task on its next scheduler tick. Because both
/// aborts fire BEFORE `sender` drops, the tasks observe
/// cancellation rather than a `BrokenPipe` on their next `.await`.
pub(crate) struct MasqueUdpFlow {
    pub(crate) sender: MasqueUdpSender,
    _request_sender: Option<H3RequestSender>,
    pub(crate) driver_task: JoinHandle<()>,
    pub(crate) reader_task: JoinHandle<()>,
    pub(crate) path_connection: Option<quinn::Connection>,
    datagram_stream_id_len: usize,
    pub(crate) last_used: Instant,
}

impl MasqueUdpRelay {
    /// Sends `payload` to `target`, opening a CONNECT-UDP flow first when there is
    /// none or the proxy closed the previous one.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: a flow is stored only after it is open; cancelling while it
    /// opens drops the half-open flow, and the payload is not sent.
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        self.ensure_flow(target).await?;

        let flow = self.flows.get_mut(target).expect("flow inserted above");
        match flow.send(payload) {
            // InvalidInput rejects only this datagram; the flow stays usable.
            Err(error) if error.kind() != io::ErrorKind::InvalidInput => {
                // The carrier is dead (for example an idle QUIC timeout): replace the
                // flow so one broken tunnel does not fail the whole association.
                tracing::debug!(target, error = %error, "MASQUE UDP flow failed, reopening it");
                self.flows.remove(target);
                let mut flow = self.open_flow(target).await?;
                let result = flow.send(payload);
                self.flows.insert(target.to_string(), flow);
                result
            }
            result => result,
        }
    }

    /// Opens a CONNECT-UDP flow to `target` when there is none or the previous
    /// one is dead. Opening can take the H3 attempt plus the H2 fallback, so a
    /// caller that bounds the datagram write should bound this step separately.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: a flow is stored only after it is open; cancelling while it
    /// opens drops the half-open flow.
    pub async fn ensure_flow(&mut self, target: &str) -> io::Result<()> {
        if self.flows.get(target).is_some_and(MasqueUdpFlow::is_dead) {
            self.flows.remove(target);
        }
        if !self.flows.contains_key(target) {
            let flow = self.open_flow(target).await?;
            make_room_for_flow(&mut self.flows, Instant::now(), MAX_UDP_FLOWS);
            self.flows.insert(target.to_string(), flow);
        }
        Ok(())
    }

    /// Receives the next datagram from any flow.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: `mpsc::Receiver::recv` is cancel-safe, and the flow is
    /// marked used only after a datagram is taken.
    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        let (target, payload) = self
            .incoming_rx
            .recv()
            .await
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "MASQUE UDP relay closed"))?;
        // A receive-heavy flow is in use too; keep it out of idle and LRU eviction.
        if let Some(flow) = self.flows.get_mut(&target) {
            flow.last_used = Instant::now();
        }
        Ok((target, payload))
    }

    async fn open_flow(&self, target: &str) -> io::Result<MasqueUdpFlow> {
        let auth_header = self.client.request_auth_header(target).await?;
        let fallback_reason = if self.client.config.use_http2_fallback && self.client.h3_udp_in_cooldown().await {
            // H3 failed recently on this relay: skip the attempt so a UDP-blocked
            // path does not pay the H3 connect bound for every new target.
            "http3_udp_cooldown".to_string()
        } else {
            let h3 = self
                .client
                .attempt_with_privacy_pass_retry(target, auth_header.clone(), |header| async move {
                    bounded_attempt(
                        "MASQUE H3 CONNECT-UDP",
                        attempt_h3_connect_udp(&self.client.config, target, header.as_ref(), self.incoming_tx.clone()),
                    )
                    .await
                })
                .await;
            let error = match h3 {
                Ok(flow) => {
                    let reason = if self.client.config.quic_migrate_after_handshake {
                        Some("path_validated_after_http3_udp_connect")
                    } else {
                        Some("http3_transport_without_rebind")
                    };
                    let status =
                        if self.client.config.quic_migrate_after_handshake { "validated" } else { "not_attempted" };
                    self.client.record_quic_migration_status(status, reason).await;
                    return Ok(flow);
                }
                Err(error) => error,
            };
            let reason = format!("http3_udp_connect_failed_{}", classify_attempt_failure(&error));
            if !self.client.config.use_http2_fallback {
                self.client.record_quic_migration_status("failed", Some(&reason)).await;
                return Err(error);
            }
            tracing::info!(target, error = %error, "MASQUE H3 UDP connect failed, falling back to HTTP/2");
            if is_h3_transport_failure(&error) {
                self.client.arm_h3_udp_cooldown().await;
            }
            reason
        };
        let h2 = self
            .client
            .attempt_with_privacy_pass_retry(target, auth_header, |header| async move {
                bounded_attempt(
                    "MASQUE H2 CONNECT-UDP",
                    attempt_h2_connect_udp(&self.client.config, target, header.as_ref(), self.incoming_tx.clone()),
                )
                .await
            })
            .await;
        match h2 {
            Ok(flow) => {
                self.client.record_quic_migration_status("http2_fallback", Some(&fallback_reason)).await;
                Ok(flow)
            }
            Err(fallback_error) => {
                self.client
                    .record_quic_migration_status("failed", Some("http3_udp_connect_failed_and_http2_fallback_failed"))
                    .await;
                Err(fallback_error)
            }
        }
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }

    pub fn quic_path_snapshot(&self, target: &str) -> Option<MasqueQuicPathSnapshot> {
        let connection = self.flows.get(target)?.path_connection.as_ref()?;
        let stats = connection.stats();
        let path = stats.path;
        Some(MasqueQuicPathSnapshot {
            current_mtu: path.current_mtu,
            max_datagram_size: connection.max_datagram_size(),
            sent_datagram_frames: stats.frame_tx.datagram,
            received_datagram_frames: stats.frame_rx.datagram,
            lost_packets: path.lost_packets,
            sent_plpmtud_probes: path.sent_plpmtud_probes,
            lost_plpmtud_probes: path.lost_plpmtud_probes,
            black_holes_detected: path.black_holes_detected,
        })
    }
}

/// Whether an H3 attempt failed in its transport (QUIC handshake, UDP blocked,
/// timeout) rather than by a proxy answer about this request (refused target,
/// auth error, malformed response). Only a transport failure says H3 is
/// unusable on this path for other targets.
fn is_h3_transport_failure(error: &io::Error) -> bool {
    !matches!(error.kind(), io::ErrorKind::PermissionDenied | io::ErrorKind::InvalidInput | io::ErrorKind::InvalidData)
}

fn make_room_for_flow(flows: &mut HashMap<String, MasqueUdpFlow>, now: Instant, max_flows: usize) {
    flows.retain(|_, flow| now.saturating_duration_since(flow.last_used) < UDP_FLOW_IDLE_TIMEOUT);
    while flows.len() >= max_flows {
        let Some(oldest) = flows.iter().min_by_key(|(_, flow)| flow.last_used).map(|(target, _)| target.clone()) else {
            break;
        };
        flows.remove(&oldest);
    }
}

impl MasqueUdpFlow {
    pub(crate) fn new(
        sender: MasqueUdpSender,
        request_sender: Option<H3RequestSender>,
        driver_task: JoinHandle<()>,
        reader_task: JoinHandle<()>,
        path_connection: Option<quinn::Connection>,
        datagram_stream_id_len: usize,
    ) -> Self {
        Self {
            sender,
            _request_sender: request_sender,
            driver_task,
            reader_task,
            path_connection,
            datagram_stream_id_len,
            last_used: Instant::now(),
        }
    }

    /// The proxy closed the tunnel (finished reader) or its carrier is gone.
    fn is_dead(&self) -> bool {
        self.reader_task.is_finished()
            || match &self.sender {
                MasqueUdpSender::H3 { connection, .. } => connection.close_reason().is_some(),
                MasqueUdpSender::H2(sender) => sender.is_closed(),
            }
    }

    fn send(&mut self, payload: &[u8]) -> io::Result<()> {
        let result =
            match &mut self.sender {
                MasqueUdpSender::H3 { connection, stream_id } => {
                    let datagram = encode_connect_udp_payload(0, payload)
                        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
                    if self.path_connection.as_ref().and_then(quinn::Connection::max_datagram_size).is_some_and(
                        |max_datagram_size| datagram.len() + self.datagram_stream_id_len > max_datagram_size,
                    ) {
                        return Err(io::Error::new(io::ErrorKind::InvalidInput, H3_DATAGRAM_TOO_LARGE));
                    }
                    let mut encoded = h3_datagram::datagram::Datagram::new(*stream_id, Bytes::from(datagram)).encode();
                    let encoded_len = encoded.remaining();
                    connection.send_datagram(encoded.copy_to_bytes(encoded_len)).map_err(h3_datagram_send_error)
                }
                MasqueUdpSender::H2(sender) => match sender.try_send(payload.to_vec()) {
                    // UDP is lossy: a full queue (the H2 stream is flow-control blocked)
                    // drops this datagram instead of failing the association.
                    Ok(()) | Err(mpsc::error::TrySendError::Full(_)) => Ok(()),
                    Err(mpsc::error::TrySendError::Closed(_)) => {
                        Err(io::Error::new(io::ErrorKind::BrokenPipe, "MASQUE H2 UDP stream closed"))
                    }
                },
            };
        if result.is_ok() {
            self.last_used = Instant::now();
        }
        result
    }
}

impl Drop for MasqueUdpFlow {
    fn drop(&mut self) {
        self.driver_task.abort();
        self.reader_task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_too_large_race_maps_to_stable_invalid_input_contract() {
        let error = h3_datagram_send_error(quinn::SendDatagramError::TooLarge);

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("masque_h3_datagram_too_large"));
    }

    fn test_flow(last_used: Instant) -> MasqueUdpFlow {
        let (sender, _receiver) = mpsc::channel(1);
        let driver_task = tokio::spawn(std::future::pending());
        let reader_task = tokio::spawn(std::future::pending());
        let mut flow = MasqueUdpFlow::new(MasqueUdpSender::H2(sender), None, driver_task, reader_task, None, 0);
        flow.last_used = last_used;
        flow
    }

    #[tokio::test]
    async fn h2_send_drops_a_datagram_when_the_queue_is_full_and_fails_once_closed() {
        let (sender, receiver) = mpsc::channel(1);
        let driver_task = tokio::spawn(std::future::pending());
        let reader_task = tokio::spawn(std::future::pending());
        let mut flow = MasqueUdpFlow::new(MasqueUdpSender::H2(sender), None, driver_task, reader_task, None, 0);

        flow.send(b"queued").expect("first datagram is queued");
        flow.send(b"dropped").expect("a full queue drops the datagram");
        drop(receiver);

        assert_eq!(flow.send(b"closed").expect_err("closed stream").kind(), io::ErrorKind::BrokenPipe);
    }

    #[tokio::test]
    async fn flow_limit_evicts_idle_then_least_recently_used_targets() {
        let mut flows = HashMap::new();
        let now = Instant::now();
        flows.insert("idle".to_string(), test_flow(now - UDP_FLOW_IDLE_TIMEOUT));
        flows.insert("old".to_string(), test_flow(now - Duration::from_secs(2)));
        flows.insert("recent".to_string(), test_flow(now - Duration::from_secs(1)));

        make_room_for_flow(&mut flows, now, 2);

        assert_eq!(flows.len(), 1);
        assert!(flows.contains_key("recent"));
    }

    #[tokio::test]
    async fn flow_limit_never_retains_more_than_the_configured_capacity() {
        let mut flows = HashMap::new();
        let now = Instant::now();
        for index in 0..=MAX_UDP_FLOWS {
            flows.insert(index.to_string(), test_flow(now));
        }

        make_room_for_flow(&mut flows, now, MAX_UDP_FLOWS);

        assert!(flows.len() < MAX_UDP_FLOWS);
    }
}
