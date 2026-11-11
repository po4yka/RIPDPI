use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns and drops every stream and datagram lease.
#[tokio::test(flavor = "current_thread")]
async fn generation_idle_starts_when_its_own_last_lease_drops() {
    let factory = LivenessFactory::default();
    let closed = Arc::clone(&factory.closed);
    let mux = RelayMux::new(
        factory,
        RelayPoolConfig { idle_timeout: Duration::from_millis(100), ..RelayPoolConfig::default() },
    );
    let old = mux.open_stream("old.example:443").await.expect("old carrier");
    closed.store(true, Ordering::SeqCst);
    let fresh = mux.open_datagram().await.expect("replacement carrier");
    assert_eq!(*fresh.get_ref(), 1);
    sleep(Duration::from_millis(150)).await;
    drop(fresh);
    drop(old);
    let reused = mux.open_datagram().await.expect("recently released replacement");
    assert_eq!(*reused.get_ref(), 1, "active use must not count as idle time");
}

/// # Cancel safety:
/// cancel-safe: the test owns and drops every datagram lease.
#[tokio::test(flavor = "current_thread")]
async fn generation_idle_expires_while_an_evicted_generation_is_still_leased() {
    let factory = LivenessFactory::default();
    let closed = Arc::clone(&factory.closed);
    let mux = RelayMux::new(
        factory,
        RelayPoolConfig { idle_timeout: Duration::from_millis(100), ..RelayPoolConfig::default() },
    );
    let old = mux.open_datagram().await.expect("old carrier");
    closed.store(true, Ordering::SeqCst);
    let fresh = mux.open_datagram().await.expect("replacement carrier");
    assert_eq!(*fresh.get_ref(), 1);
    drop(fresh);
    let health = mux.health();
    assert_eq!(health.busy_streams, 1);
    assert_eq!(health.idle_streams, 1, "old lease must not mark the replacement busy");
    sleep(Duration::from_millis(150)).await;
    let replacement = mux.open_datagram().await.expect("expired idle replacement");
    assert_eq!(*replacement.get_ref(), 2, "old lease must not prevent idle expiry");
    drop(old);
}

/// # Cancel safety:
/// cancel-safe: the test owns and drops the active stream before returning.
#[tokio::test(flavor = "current_thread")]
async fn generation_zero_idle_timeout_does_not_evict_a_reserved_active_stream() {
    let mux = RelayMux::new(
        LivenessFactory::default(),
        RelayPoolConfig { idle_timeout: Duration::ZERO, ..RelayPoolConfig::default() },
    );
    let stream = mux.open_stream("active.example:443").await.expect("active stream");
    let health = mux.health();
    assert_eq!(health.busy_streams, 1);
    assert_eq!(health.evictions, 0, "an active lease must protect its carrier from idle expiry");
    drop(stream);
    assert_eq!(mux.health().evictions, 1, "the released carrier must now expire");
}
