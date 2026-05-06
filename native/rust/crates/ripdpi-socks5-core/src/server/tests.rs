#[cfg(test)]
mod test {
    use crate::server::Socks5Server;
    use tokio_test::block_on;

    use super::AcceptAuthentication;

    #[test]
    fn test_bind() {
        let f = async {
            let _server = Socks5Server::<AcceptAuthentication>::bind("127.0.0.1:1080").await.unwrap();
        };

        block_on(f);
    }
}
