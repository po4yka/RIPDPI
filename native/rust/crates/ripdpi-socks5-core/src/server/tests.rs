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

    // cancel-safe: cancellation drops only the in-memory test streams.
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
}
