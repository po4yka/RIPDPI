//! Minimal relay-side entry point demonstrating the spoofer wiring.
//!
//! Linux-only in substance: the raw-socket injection it would drive needs
//! `CAP_NET_RAW`/`CAP_NET_ADMIN`. Off Linux the binary still builds and runs,
//! printing a notice. This is a demonstration harness, not the production
//! relay; default-off posture is preserved (it reads `enabled` from the config
//! it builds and short-circuits when false).

#[cfg(target_os = "linux")]
fn main() {
    use ripdpi_tls_spoof::{RelaySpoofer, SpoofMethod, TlsSpoofConfig};

    // In a real relay this config arrives from the client's SpoofRequest over
    // the control channel; here we build a representative one. Default-off
    // unless `enabled` is set true.
    let config =
        TlsSpoofConfig { enabled: false, method: SpoofMethod::WrongMd5, decoy_sni: "www.wikipedia.org".to_string() };

    if !config.enabled {
        eprintln!("ripdpi-tls-spoof: spoofer disabled (default-off); nothing to do");
        return;
    }

    if let Err(err) = config.validate() {
        eprintln!("ripdpi-tls-spoof: invalid config: {err}");
        std::process::exit(1);
    }

    // A real relay would: connect to the upstream `destination`, capture the
    // real ClientHello, then call `spoof_before_handshake` with that stream.
    // We only report capability state here to keep the demo side-effect-free.
    let caps = ripdpi_tls_spoof::inject::has_raw_socket_caps();
    eprintln!("ripdpi-tls-spoof: raw-socket caps available = {caps}");

    let _spoofer = RelaySpoofer::new();
    eprintln!("ripdpi-tls-spoof: spoofer constructed; wire to upstream connector to use");
}

#[cfg(not(target_os = "linux"))]
fn main() {
    eprintln!("ripdpi-tls-spoof: linux only");
}
