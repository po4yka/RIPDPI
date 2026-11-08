use super::{TargetAddr, associate, connect};
use std::io;
use tokio_test::io::Builder;

/// # Cancel safety:
/// cancel-safe: the test owns and discards each stream.
#[tokio::test]
async fn loc09_connect_rejects_invalid_header_before_reading_body() {
    for (version, reserved) in [(4, 0), (5, 127)] {
        let mut stream =
            Builder::new().write(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 53]).read(&[version, 0, reserved, 1]).build();
        let error = connect(&mut stream, &TargetAddr::Ip("127.0.0.1:53".parse().unwrap())).await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }
}

/// # Cancel safety:
/// cancel-safe: the test owns and discards each stream.
#[tokio::test]
async fn loc09_associate_rejects_invalid_header_before_reading_body() {
    for (version, reserved) in [(4, 0), (5, 127)] {
        let mut stream = Builder::new().write(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).read(&[version, 0, reserved, 1]).build();
        let error = associate(&mut stream, std::net::Ipv4Addr::LOCALHOST.into()).await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }
}
