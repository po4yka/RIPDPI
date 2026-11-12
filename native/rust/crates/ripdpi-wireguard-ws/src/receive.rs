use std::future::Future;
use std::time::Duration;

use futures::{Sink, SinkExt, Stream, StreamExt, stream::SplitStream};
use tokio::time::{Instant, timeout_at};
use tokio_tungstenite::tungstenite::{Error, Message, error::ProtocolError};

use crate::{SharedSink, WsCarrierError, unframe_message};

const CLOSE_REPLY_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Default)]
pub(crate) enum ReadState {
    #[default]
    Open,
    Closing(Instant),
    Closed,
}

/// # Cancel safety:
/// cancel-safe: framing stays in the stream; Close state and its absolute
/// deadline stay in the carrier when a pending receive is dropped.
pub(crate) async fn recv_datagram<S>(stream: &mut S, state: &mut ReadState) -> Result<Vec<u8>, WsCarrierError>
where
    S: Sink<Message, Error = Error> + Stream<Item = Result<Message, Error>> + Unpin,
{
    let result = read_datagram(stream, state).await;
    if matches!(state, ReadState::Closing(_)) {
        return finish_close(state, stream.flush()).await;
    }
    result
}

/// # Cancel safety:
/// cancel-safe: cancellation releases the Sink guard; framing, buffered writes,
/// Close state, and its original deadline remain owned by the split carrier.
pub(crate) async fn recv_split_datagram<S>(
    stream: &mut SplitStream<S>,
    sink: &SharedSink<S>,
    state: &mut ReadState,
) -> Result<Vec<u8>, WsCarrierError>
where
    S: Sink<Message, Error = Error> + Stream<Item = Result<Message, Error>> + Unpin,
{
    let result = read_datagram(stream, state).await;
    if matches!(state, ReadState::Closing(_)) {
        // The Stream poll guard is gone before the Sink lock is acquired.
        // Senders never acquire the receiver lock, so there is no lock cycle.
        return finish_close(state, async {
            let mut sink = sink.lock().await;
            match sink.flush().await {
                // SplitSink consumes the cancelled datagram slot before this
                // rejection. Flush again to send the already-queued Close;
                // never replay the rejected datagram.
                Err(Error::Protocol(ProtocolError::SendAfterClosing)) => sink.flush().await,
                result => result,
            }
        })
        .await;
    }
    result
}

/// # Cancel safety:
/// cancel-safe: the stream retains framing and a received Close updates the
/// caller-owned state before this future returns.
async fn read_datagram<S>(stream: &mut S, state: &mut ReadState) -> Result<Vec<u8>, WsCarrierError>
where
    S: Stream<Item = Result<Message, Error>> + Unpin,
{
    if !matches!(state, ReadState::Open) {
        return Err(WsCarrierError::Closed);
    }
    loop {
        match stream.next().await {
            // The transport flushes queued Pong on its next read without blocking inbound data.
            Some(Ok(Message::Ping(_) | Message::Pong(_))) => continue,
            Some(Ok(Message::Close(_))) => {
                *state = ReadState::Closing(Instant::now() + CLOSE_REPLY_TIMEOUT);
                return Err(WsCarrierError::Closed);
            }
            None => {
                *state = ReadState::Closed;
                return Err(WsCarrierError::Closed);
            }
            Some(message) => return unframe_message(message?),
        }
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops only the pending flush, whose bytes stay in
/// the Sink. The caller-owned Closing state retains the original deadline.
async fn finish_close<F>(state: &mut ReadState, flush: F) -> Result<Vec<u8>, WsCarrierError>
where
    F: Future<Output = Result<(), Error>>,
{
    let ReadState::Closing(deadline) = *state else {
        return Err(WsCarrierError::Closed);
    };
    let result = timeout_at(deadline, flush).await;
    *state = ReadState::Closed;
    match result {
        Ok(Ok(()) | Err(Error::ConnectionClosed | Error::AlreadyClosed)) => Err(WsCarrierError::Closed),
        Ok(Err(error)) => Err(WsCarrierError::Transport(error)),
        Err(_) => Err(WsCarrierError::Transport(Error::Io(std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            "WebSocket Close reply timed out",
        )))),
    }
}
