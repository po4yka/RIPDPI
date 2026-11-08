use super::*;
use crate::reality::{connect_reality_h2_tls_over, connect_reality_tls_over};

/// # Cancel safety:
/// cancel-safe: both ends of each in-memory transport are owned by this test.
#[tokio::test]
async fn invalid_identity_is_refused_before_a_reality_client_hello() {
    let public = PublicKey::from(&StaticSecret::from(REALITY_SERVER_SECRET));
    let public_key = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(public.as_bytes());
    let config = VlessRealityConfig::from_strings(
        "127.0.0.1",
        443,
        "550e8400-e29b-41d4-a716-446655440000",
        "bücher.test",
        &public_key,
        "abcd1234",
        "chrome_stable",
    )
    .expect("REALITY configuration");
    for require_h2 in [false, true] {
        let (transport, _peer) = tokio::io::duplex(64);
        let handshake = async {
            if require_h2 {
                connect_reality_h2_tls_over(transport, &config).await
            } else {
                connect_reality_tls_over(transport, &config).await
            }
        };
        let result = tokio::time::timeout(Duration::from_secs(1), handshake)
            .await
            .expect("invalid name must not wait for a TLS peer");
        let Err(error) = result else { panic!("invalid REALITY identity was accepted") };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }
}
