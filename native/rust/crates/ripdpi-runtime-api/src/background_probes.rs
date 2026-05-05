use ripdpi_proxy_config::NetworkSnapshot;

/// Coarse port trait for background probe scheduling.
///
/// Implementations drive reprobe logic and warmup requests.
/// The port is passed by `Arc<dyn BackgroundProbes>` so proxy-runtime
/// never takes a direct dependency on the concrete reprobe types.
pub trait BackgroundProbes: Send + Sync {
    /// Called when OS network state changes; drives reprobe scheduling.
    fn notify_network_change(&self, snapshot: NetworkSnapshot);

    /// Fire-and-forget warmup request for a given authority+protocol.
    fn request_warmup(&self, authority: &str, is_udp: bool);
}
