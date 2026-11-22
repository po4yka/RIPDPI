use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use rustls::client::{WebPkiServerVerifier, danger::ServerCertVerifier};
use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};

use crate::test_fixtures::make_cert;

pub(super) fn with_trusted_https_peer(
    serve: impl FnOnce(StreamOwned<ServerConnection, TcpStream>) + Send,
    probe: impl FnOnce(u16, &Arc<dyn ServerCertVerifier>),
) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let (cert, key) = make_cert(&["localhost".into()]);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = ServerConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], key)
        .unwrap();
    let mut roots = RootCertStore::empty();
    roots.add(cert).unwrap();
    let verifier: Arc<dyn ServerCertVerifier> =
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider).build().unwrap();
    thread::scope(|scope| {
        let peer = scope.spawn(|| {
            let deadline = Instant::now() + Duration::from_secs(5);
            let socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("local TLS peer did not receive the request: {error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let stream = StreamOwned::new(ServerConnection::new(Arc::new(config)).unwrap(), socket);
            serve(stream);
        });
        probe(port, &verifier);
        peer.join().unwrap();
    });
}
