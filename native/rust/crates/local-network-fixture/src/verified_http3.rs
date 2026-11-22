use std::io;
use std::net::UdpSocket;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use bytes::Bytes;
use http::{Method, Response, StatusCode, header};
use quinn::crypto::rustls::QuicServerConfig;
use rustls::ServerConfig;

use crate::types::IO_POLL_DELAY;
use crate::util::other_io;

pub(crate) fn start(
    socket: UdpSocket,
    stop: Arc<AtomicBool>,
    server_config: &ServerConfig,
) -> io::Result<JoinHandle<()>> {
    let mut tls = server_config.clone();
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let quic = QuicServerConfig::try_from(tls).map_err(other_io)?;
    let config = quinn::ServerConfig::with_crypto(Arc::new(quic));
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    socket.set_nonblocking(true)?;
    let endpoint = {
        let _context = runtime.enter();
        quinn::Endpoint::new(quinn::EndpointConfig::default(), Some(config), socket, Arc::new(quinn::TokioRuntime))?
    };

    Ok(thread::spawn(move || {
        runtime.block_on(async move {
            while !stop.load(Ordering::Relaxed) {
                tokio::select! {
                    incoming = endpoint.accept() => {
                        let Some(incoming) = incoming else { break };
                        tokio::spawn(async move {
                            // The diagnostic comparison can wait up to 15 seconds.
                            let _ = tokio::time::timeout(Duration::from_secs(20), serve(incoming)).await;
                        });
                    }
                    _ = tokio::time::sleep(IO_POLL_DELAY) => {}
                }
            }
            endpoint.close(0u32.into(), b"fixture stop");
        });
    }))
}

/// Serve the first request from a fixture connection.
///
/// # Cancel safety:
/// cancel-safe: dropping the future releases its request stream and connection.
async fn serve(incoming: quinn::Incoming) -> io::Result<()> {
    let connection = incoming.await.map_err(other_io)?;
    let mut server =
        h3::server::Connection::new(h3_quinn::Connection::new(connection.clone())).await.map_err(other_io)?;
    let Some(resolver) = server.accept().await.map_err(other_io)? else {
        return Ok(());
    };
    let (request, mut stream) = resolver.resolve_request().await.map_err(other_io)?;
    let verified_object = request.method() == Method::GET && request.uri().path() == "/verified-short";
    let response = if verified_object {
        Response::builder().status(StatusCode::OK).header(header::CONTENT_LENGTH, "2")
    } else {
        Response::builder().status(StatusCode::NOT_FOUND).header(header::CONTENT_LENGTH, "0")
    };
    stream.send_response(response.body(()).map_err(other_io)?).await.map_err(other_io)?;
    if verified_object {
        stream.send_data(Bytes::from_static(b"o")).await.map_err(other_io)?;
        // Retain the stream without a FIN so the client observes a body stall.
        let _ = connection.closed().await;
        drop(stream);
    } else {
        stream.finish().await.map_err(other_io)?;
    }
    Ok(())
}
