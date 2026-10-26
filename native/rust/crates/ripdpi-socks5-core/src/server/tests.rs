#[cfg(test)]
mod test {
    use crate::server::{Config, DenyAuthentication, Socks5Server, Socks5Socket};
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_test::block_on;

    use super::AcceptAuthentication;

    #[test]
    fn test_bind() {
        let f = async {
            let _server = Socks5Server::<AcceptAuthentication>::bind("127.0.0.1:1080").await.unwrap();
        };

        block_on(f);
    }

    /// # Cancel safety
    ///
    /// cancel-safe: cancellation drops only the in-memory test streams.
    #[tokio::test(flavor = "current_thread")]
    async fn udp_associate_without_control_peer_sends_failure_reply() {
        let (mut client, server) = tokio::io::duplex(64);
        let mut config = Config::<DenyAuthentication>::default();
        config.set_udp_support(true);
        let socket = Socks5Socket::new(server, Arc::new(config));
        let exchange = async move {
            client.write_all(&[5, 1, 0]).await.unwrap();
            let mut auth_reply = [0u8; 2];
            client.read_exact(&mut auth_reply).await.unwrap();
            assert_eq!(auth_reply, [5, 0]);
            client.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            let mut reply = [0u8; 10];
            client.read_exact(&mut reply).await.unwrap();
            reply
        };
        let (result, reply) = tokio::join!(socket.upgrade_to_socks5(), exchange);
        assert!(result.is_err());
        assert_eq!(reply, [5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
    }

    /// Set in the child process that runs the accept-error test under a low descriptor limit.
    #[cfg(unix)]
    const ACCEPT_ERROR_CHILD: &str = "RIPDPI_SOCKS5_ACCEPT_ERROR_CHILD";

    /// # Cancel safety
    ///
    /// cancel-safe: cancellation drops only local test sockets.
    #[cfg(unix)]
    #[tokio::test(flavor = "current_thread")]
    async fn incoming_survives_an_accept_error() {
        use tokio_stream::StreamExt;

        if std::env::var_os(ACCEPT_ERROR_CHILD).is_none() {
            // Re-run this test alone under `ulimit -n`, so accept() can fail with EMFILE
            // without starving the other tests of this process.
            let status = std::process::Command::new("sh")
                .arg("-c")
                .arg("ulimit -n 64 && exec \"$0\" --exact server::test::incoming_survives_an_accept_error --test-threads=1")
                .arg(std::env::current_exe().unwrap())
                .env(ACCEPT_ERROR_CHILD, "1")
                .status()
                .unwrap();
            assert!(status.success(), "child test failed: {status}");
            return;
        }
        let server = Socks5Server::<AcceptAuthentication>::bind("127.0.0.1:0").await.unwrap();
        let addr = server.listener.local_addr().unwrap();
        let mut clients = Vec::new();
        while let Ok(client) = std::net::TcpStream::connect(addr) {
            clients.push(client);
        }
        let mut incoming = server.incoming();
        assert!(incoming.next().await.unwrap().is_err(), "accept must fail with no free descriptor");
        clients.truncate(clients.len().saturating_sub(4));
        assert!(incoming.next().await.unwrap().is_ok(), "the stream must accept again after the error");
    }
}
