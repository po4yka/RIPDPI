use super::*;
use tokio::io::BufStream;

/// # Cancel safety:
/// cancel-safe: cancellation drops the local listener and streams.
async fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let (client, server) = tokio::join!(TcpStream::connect(listener.local_addr().unwrap()), listener.accept());
    (client.unwrap(), server.unwrap().0)
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned stream and partial test reply.
async fn reply(mut client: TcpStream, expected: [u8; 2]) {
    let mut bytes = [0; 2];
    tokio::time::timeout(Duration::from_millis(200), client.read_exact(&mut bytes))
        .await
        .expect("reply must leave the buffered writer")
        .unwrap();
    assert_eq!(bytes, expected);
}

/// # Cancel safety:
/// cancel-safe: cancellation drops local transports and protocol state.
#[tokio::test(flavor = "current_thread")]
async fn method_selection_flushes_buffered_transport() {
    for (method, expected) in [(0, [5, 0]), (1, [5, 255])] {
        let (mut client, server) = pair().await;
        client.write_all(&[5, 1, method]).await.unwrap();
        let mut buffered = BufStream::new(server);
        let handling = async {
            let result = Socks5ServerProtocol::accept_no_auth(&mut buffered).await;
            assert_eq!(result.is_ok(), method == 0);
        };
        tokio::join!(handling, reply(client, expected));
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops local transports and authentication state.
#[tokio::test(flavor = "current_thread")]
async fn password_status_flushes_buffered_transport() {
    for accepted in [true, false] {
        let (mut client, server) = pair().await;
        client.write_all(&[1, 1, b'u', 1, b'p']).await.unwrap();
        let mut buffered = BufStream::new(server);
        let handling = async {
            let started = PasswordAuthenticationStarted::new(&mut buffered);
            let (username, password, auth) = started.read_username_password().await.unwrap();
            assert_eq!((username.as_str(), password.as_str()), ("u", "p"));
            if accepted {
                let _authenticated = auth.accept().await.unwrap();
            } else {
                auth.reject().await.unwrap();
            }
        };
        tokio::join!(handling, reply(client, [1, if accepted { 0 } else { 255 }]));
    }
}
