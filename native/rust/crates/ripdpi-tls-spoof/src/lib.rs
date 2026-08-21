//! Relay-side TLS pre-handshake SNI-desync spoofer.
//!
//! # Spike verdict
//!
//! **Relay-only.** The technique injects a forged decoy ClientHello as a
//! corrupted raw TCP segment ahead of the real handshake: a DPI middlebox on
//! the path records the *decoy* SNI and applies its policy to that, while the
//! real server's TCP stack discards the corrupted segment so the genuine
//! VLESS+Reality / xHTTP handshake proceeds untouched.
//!
//! Emitting that segment needs `CAP_NET_RAW` + `CAP_NET_ADMIN` and the Linux
//! `TCP_REPAIR` facility. A **non-rooted Android device cannot grant any of
//! these**, so on-device spoofing is infeasible. The design therefore runs on
//! the **relay hop**: the on-device client signals its intent
//! ([`SpoofRequest`]) and the relay — which has the privileges — performs the
//! injection in front of the upstream handshake.
//!
//! # Layout
//!
//! * [`config`] — [`SpoofMethod`], [`TlsSpoofConfig`] (default-off), validation.
//! * [`client_hello`] — [`forge_decoy_client_hello`]: JA3/JA4-preserving SNI
//!   surgery on a record-framed ClientHello.
//! * [`telemetry`] — pull-model `AtomicU64` tallies for successful and failed
//!   injections (`note_spoofed_connection` / `note_spoof_failed`).
//! * [`signaling`] — [`SpoofRequest`]: the client -> relay intent wire type.
//! * [`segment`] — [`SpoofSegmentParams`] + [`build_spoof_segment`]: pure
//!   IPv4+TCP segment construction with per-method corruption. **All-target**
//!   (no `cfg` gate); fully unit-tested on macOS.
//! * [`inject`] — Linux-only raw-socket send path: `TCP_REPAIR` seq read +
//!   `SOCK_RAW`/`IPPROTO_RAW` sendto. Capability probe + `inject_decoy_segment`
//!   (default-off; Linux-CI verified).
//!
//! # Default-off
//!
//! Nothing happens unless a caller sets [`TlsSpoofConfig::enabled`] true and
//! the config validates. [`RelaySpoofer::spoof_before_handshake`] returns
//! `Ok(())` as a no-op otherwise.

pub mod client_hello;
pub mod config;
mod error;
pub mod inject;
pub mod segment;
pub mod signaling;
pub mod telemetry;

pub use config::{SpoofMethod, TlsSpoofConfig};
pub use error::SpoofError;

/// Composes the spoof pipeline for use on the relay hop.
///
/// Intended call site: the relay's upstream connector invokes
/// [`RelaySpoofer::spoof_before_handshake`] *after* the TCP connection to the
/// real upstream is established but *before* it forwards the genuine
/// VLESS+Reality / xHTTP ClientHello, so the decoy segment lands first on the
/// wire.
#[derive(Debug, Default, Clone, Copy)]
pub struct RelaySpoofer;

impl RelaySpoofer {
    /// Construct a spoofer. Stateless; all behavior is driven by the passed
    /// [`TlsSpoofConfig`].
    #[must_use]
    pub fn new() -> Self {
        RelaySpoofer
    }

    /// Run the pre-handshake spoof for one upstream connection.
    ///
    /// Steps:
    /// 1. No-op `Ok(())` if `config.enabled` is false (default-off).
    /// 2. Validate `config` (rejects empty / IP-literal / malformed decoy SNI).
    /// 3. Forge the decoy ClientHello from `real_client_hello`.
    /// 4. On Linux with raw-socket capabilities present, inject the decoy
    ///    segment. A successful injection records the telemetry tally; a failed
    ///    one logs the failure class (`io::ErrorKind`) and records a failure
    ///    tally, but never aborts the real handshake. Where capabilities are
    ///    absent (e.g. the macOS dev host, or an unprivileged relay), forging
    ///    and validation still run and succeed, but no segment is emitted.
    ///
    /// `conn` is the established TCP connection to the real upstream; it is
    /// used only as the injection target and is never written to by this
    /// function on the no-capability path.
    pub fn spoof_before_handshake(
        &self,
        config: &TlsSpoofConfig,
        conn: &std::net::TcpStream,
        real_client_hello: &[u8],
    ) -> Result<(), SpoofError> {
        if !config.enabled {
            return Ok(());
        }
        config.validate()?;
        let forged = client_hello::forge_decoy_client_hello(real_client_hello, &config.decoy_sni)?;

        if inject::has_raw_socket_caps() {
            // An injection I/O failure must not abort the real handshake, but
            // it is never silent: the failure class is logged and tallied so
            // relay operators can see why spoofing is not happening.
            match inject::inject_decoy_segment(conn, &forged, config.method) {
                Ok(()) => telemetry::note_spoofed_connection(),
                Err(err) => {
                    // AC7 redaction: log the io::ErrorKind class only —
                    // never the decoy hostname, destination, or method value.
                    tracing::warn!(kind = %err.kind(), "tls.spoof_inject_failed");
                    telemetry::note_spoof_failed();
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn loopback_stream() -> std::net::TcpStream {
        // Loopback only — exempt from the VpnService.protect invariant.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::net::TcpStream::connect(addr).unwrap()
    }

    fn record_framed_client_hello(host: &str) -> Vec<u8> {
        // Minimal record-framed ClientHello with a single SNI extension.
        let host = host.as_bytes();
        let mut sni = Vec::new();
        sni.extend_from_slice(&((1 + 2 + host.len()) as u16).to_be_bytes());
        sni.push(0x00);
        sni.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni.extend_from_slice(host);

        let mut exts = Vec::new();
        exts.extend_from_slice(&0x0000u16.to_be_bytes());
        exts.extend_from_slice(&(sni.len() as u16).to_be_bytes());
        exts.extend_from_slice(&sni);

        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]);
        body.extend_from_slice(&[0xAB; 32]);
        body.push(0x00);
        body.extend_from_slice(&0x0002u16.to_be_bytes());
        body.extend_from_slice(&0x1301u16.to_be_bytes());
        body.push(0x01);
        body.push(0x00);
        body.extend_from_slice(&(exts.len() as u16).to_be_bytes());
        body.extend_from_slice(&exts);

        let mut hs = vec![0x01];
        let n = body.len();
        hs.push(((n >> 16) & 0xFF) as u8);
        hs.push(((n >> 8) & 0xFF) as u8);
        hs.push((n & 0xFF) as u8);
        hs.extend_from_slice(&body);

        let mut rec = vec![0x16, 0x03, 0x01];
        rec.extend_from_slice(&(hs.len() as u16).to_be_bytes());
        rec.extend_from_slice(&hs);
        rec
    }

    #[test]
    fn disabled_is_noop() {
        let spoofer = RelaySpoofer::new();
        let cfg = TlsSpoofConfig::default(); // enabled = false
        let stream = loopback_stream();
        // Even garbage hello bytes are fine: disabled short-circuits first.
        assert_eq!(spoofer.spoof_before_handshake(&cfg, &stream, &[0xFF, 0x00]), Ok(()));
    }

    #[test]
    fn enabled_invalid_config_errors() {
        let spoofer = RelaySpoofer::new();
        let cfg = TlsSpoofConfig {
            enabled: true,
            method: SpoofMethod::WrongAck,
            decoy_sni: "1.2.3.4".to_string(), // IP literal -> rejected
        };
        let stream = loopback_stream();
        let ch = record_framed_client_hello("real.example.com");
        assert_eq!(spoofer.spoof_before_handshake(&cfg, &stream, &ch), Err(SpoofError::SniIsIpLiteral));
    }

    #[test]
    fn enabled_valid_config_forges_without_caps() {
        // On the macOS dev host has_raw_socket_caps() is false, so this
        // exercises validate + forge and returns Ok with no emission.
        let spoofer = RelaySpoofer::new();
        let cfg =
            TlsSpoofConfig { enabled: true, method: SpoofMethod::WrongMd5, decoy_sni: "www.wikipedia.org".to_string() };
        let stream = loopback_stream();
        let ch = record_framed_client_hello("real.example.com");
        assert_eq!(spoofer.spoof_before_handshake(&cfg, &stream, &ch), Ok(()));
    }

    #[test]
    fn enabled_malformed_hello_errors() {
        let spoofer = RelaySpoofer::new();
        let cfg =
            TlsSpoofConfig { enabled: true, method: SpoofMethod::WrongAck, decoy_sni: "decoy.example.com".to_string() };
        let stream = loopback_stream();
        assert_eq!(
            spoofer.spoof_before_handshake(&cfg, &stream, &[0x16, 0x03, 0x01]),
            Err(SpoofError::MalformedClientHello)
        );
    }
}
