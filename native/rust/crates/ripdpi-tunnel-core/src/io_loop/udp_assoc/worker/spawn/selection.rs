use std::future::Future;
use std::task::Poll;

use tokio_util::sync::CancellationToken;

use crate::io_loop::udp_assoc::association_state::OutboundDatagram;
use crate::session::UdpSession;
use crate::session::udp::UdpSessionEvent;

pub(super) enum UdpWork {
    Outbound(Option<OutboundDatagram>),
    Received(std::io::Result<Option<UdpSessionEvent>>),
}

/// # Cancel safety:
/// cancel-safe: dropping the selector loses no valid datagram or outbound queue item.
/// The receive future may already have discarded malformed frames, as its contract permits.
pub(super) async fn next_udp_work(
    session: &UdpSession,
    outbound: &mut tokio::sync::mpsc::Receiver<OutboundDatagram>,
    cancel: &CancellationToken,
    prefer_receive: &mut bool,
) -> Option<UdpWork> {
    if cancel.is_cancelled() {
        return None;
    }
    let mut received = std::pin::pin!(session.recv_event(cancel.clone()));
    let mut outbound = std::pin::pin!(outbound.recv());
    let mut cancelled = std::pin::pin!(cancel.cancelled());
    std::future::poll_fn(|cx| {
        if cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(None);
        }
        let receive_first = *prefer_receive;
        // Rotate even after Pending: a receive that discards malformed input can exhaust the task budget.
        *prefer_receive = !receive_first;
        if receive_first {
            if let Poll::Ready(reply) = received.as_mut().poll(cx) {
                return Poll::Ready(Some(UdpWork::Received(reply)));
            }
            if let Poll::Ready(datagram) = outbound.as_mut().poll(cx) {
                return Poll::Ready(Some(UdpWork::Outbound(datagram)));
            }
        } else {
            if let Poll::Ready(datagram) = outbound.as_mut().poll(cx) {
                return Poll::Ready(Some(UdpWork::Outbound(datagram)));
            }
            if let Poll::Ready(reply) = received.as_mut().poll(cx) {
                return Poll::Ready(Some(UdpWork::Received(reply)));
            }
        }
        Poll::Pending
    })
    .await
}
