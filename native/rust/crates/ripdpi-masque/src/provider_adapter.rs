//! `MasqueProviderAdapter` trait — the planned decoupling surface for
//! Cloudflare-direct, Privacy Pass, and bearer/preshared auth flows.
//!
//! This module ships the *trait + stub implementations* only. The
//! existing `auth.rs` continues to branch on `MasqueAuthMode` directly;
//! the migration to dispatch through `Arc<dyn MasqueProviderAdapter>`
//! is tracked under
//! `docs/tasks/issues/extract-masque-provider-adapter-trait-to-decouple-cloudflare.md`.
//!
//! Shipping the trait alone (without rewiring callers) lets new
//! out-of-tree provider integrations design against a stable surface
//! while the in-tree refactor is sequenced separately.

use crate::config::MasqueAuthMode;

/// Stable adapter surface for a MASQUE auth provider.
///
/// Implementations describe how a single provider:
///
/// 1. Identifies itself (`provider_id`),
/// 2. Reports the underlying auth mode it pairs with (`auth_mode`),
/// 3. Whether it ever wants the deployer-supplied Privacy Pass retry
///    flow to fire (`uses_privacy_pass_retry`).
///
/// The richer methods (header construction, challenge classification,
/// retry policy) will be added when the in-tree refactor lands. The
/// initial trait is intentionally narrow so the adapter shape can be
/// reviewed without a sweeping rewrite of `auth.rs`.
pub trait MasqueProviderAdapter: Send + Sync {
    /// Short, stable identifier (e.g. `"cloudflare_mtls"`,
    /// `"privacy_pass"`, `"bearer"`, `"preshared"`, `"none"`).
    fn provider_id(&self) -> &'static str;

    /// Which `MasqueAuthMode` this adapter pairs with on the wire.
    fn auth_mode(&self) -> MasqueAuthMode;

    /// Whether this adapter ever requests the deployer-supplied
    /// Privacy Pass retry flow. Only the Privacy Pass adapter returns
    /// `true`; Cloudflare-direct mTLS does not.
    fn uses_privacy_pass_retry(&self) -> bool;

    /// Whether this adapter requires a client certificate at TLS
    /// handshake time. Only `CloudflareDirectAdapter` returns `true`
    /// today; the other adapters authenticate at the HTTP layer with
    /// headers and bearer tokens.
    ///
    /// Default = `false` so future adapter implementors that don't
    /// touch client certs need not override.
    fn requires_client_certificate(&self) -> bool {
        false
    }

    /// Whether this adapter wants the optional `sec-ch-geohash`
    /// header attached to outbound requests. Cloudflare's edge uses
    /// this header to route requests by geohash; other providers
    /// don't expect it.
    ///
    /// Default = `false`.
    fn wants_geohash_header(&self) -> bool {
        false
    }
}

/// No-auth adapter. Pairs with `MasqueAuthMode::None`.
pub struct NoneAdapter;

impl MasqueProviderAdapter for NoneAdapter {
    fn provider_id(&self) -> &'static str {
        "none"
    }
    fn auth_mode(&self) -> MasqueAuthMode {
        MasqueAuthMode::None
    }
    fn uses_privacy_pass_retry(&self) -> bool {
        false
    }
}

/// Bearer-token adapter.
pub struct BearerAdapter;

impl MasqueProviderAdapter for BearerAdapter {
    fn provider_id(&self) -> &'static str {
        "bearer"
    }
    fn auth_mode(&self) -> MasqueAuthMode {
        MasqueAuthMode::Bearer
    }
    fn uses_privacy_pass_retry(&self) -> bool {
        false
    }
}

/// Preshared `Proxy-Authorization` adapter.
pub struct PresharedAdapter;

impl MasqueProviderAdapter for PresharedAdapter {
    fn provider_id(&self) -> &'static str {
        "preshared"
    }
    fn auth_mode(&self) -> MasqueAuthMode {
        MasqueAuthMode::Preshared
    }
    fn uses_privacy_pass_retry(&self) -> bool {
        false
    }
}

/// Privacy Pass adapter (deployer-supplied provider drives the retry
/// challenge flow).
pub struct PrivacyPassAdapter;

impl MasqueProviderAdapter for PrivacyPassAdapter {
    fn provider_id(&self) -> &'static str {
        "privacy_pass"
    }
    fn auth_mode(&self) -> MasqueAuthMode {
        MasqueAuthMode::PrivacyPass
    }
    fn uses_privacy_pass_retry(&self) -> bool {
        true
    }
}

/// Cloudflare-direct mTLS adapter (client certificate + optional
/// `sec-ch-geohash` header). Pairs with `MasqueAuthMode::CloudflareMtls`.
pub struct CloudflareDirectAdapter;

impl MasqueProviderAdapter for CloudflareDirectAdapter {
    fn provider_id(&self) -> &'static str {
        "cloudflare_mtls"
    }
    fn auth_mode(&self) -> MasqueAuthMode {
        MasqueAuthMode::CloudflareMtls
    }
    fn uses_privacy_pass_retry(&self) -> bool {
        false
    }
    fn requires_client_certificate(&self) -> bool {
        true
    }
    fn wants_geohash_header(&self) -> bool {
        true
    }
}

/// Pick the adapter matching a configured `MasqueAuthMode`.
///
/// Returned as `Box<dyn MasqueProviderAdapter>` so the caller can hold
/// adapters with different concrete types behind a single handle. The
/// in-tree code does not consume this yet; once the refactor lands it
/// will replace the `match` on `MasqueAuthMode` in `auth.rs`.
pub fn adapter_for(mode: MasqueAuthMode) -> Box<dyn MasqueProviderAdapter> {
    match mode {
        MasqueAuthMode::None => Box::new(NoneAdapter),
        MasqueAuthMode::Bearer => Box::new(BearerAdapter),
        MasqueAuthMode::Preshared => Box::new(PresharedAdapter),
        MasqueAuthMode::PrivacyPass => Box::new(PrivacyPassAdapter),
        MasqueAuthMode::CloudflareMtls => Box::new(CloudflareDirectAdapter),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adapter_for_each_mode_reports_consistent_metadata() {
        let cases = [
            (MasqueAuthMode::None, "none", false),
            (MasqueAuthMode::Bearer, "bearer", false),
            (MasqueAuthMode::Preshared, "preshared", false),
            (MasqueAuthMode::PrivacyPass, "privacy_pass", true),
            (MasqueAuthMode::CloudflareMtls, "cloudflare_mtls", false),
        ];
        for (mode, expected_id, expected_pp_retry) in cases {
            let adapter = adapter_for(mode);
            assert_eq!(adapter.provider_id(), expected_id, "id mismatch for {mode:?}");
            assert_eq!(adapter.auth_mode(), mode, "auth_mode round-trip mismatch for {mode:?}");
            assert_eq!(
                adapter.uses_privacy_pass_retry(),
                expected_pp_retry,
                "uses_privacy_pass_retry mismatch for {mode:?}",
            );
        }
    }

    #[test]
    fn only_privacy_pass_adapter_requests_retry_flow() {
        for mode in
            [MasqueAuthMode::None, MasqueAuthMode::Bearer, MasqueAuthMode::Preshared, MasqueAuthMode::CloudflareMtls]
        {
            assert!(!adapter_for(mode).uses_privacy_pass_retry(), "{mode:?} must NOT request privacy-pass retry");
        }
        assert!(
            adapter_for(MasqueAuthMode::PrivacyPass).uses_privacy_pass_retry(),
            "privacy_pass adapter must request retry flow",
        );
    }

    #[test]
    fn only_cloudflare_adapter_requires_client_certificate() {
        for mode in
            [MasqueAuthMode::None, MasqueAuthMode::Bearer, MasqueAuthMode::Preshared, MasqueAuthMode::PrivacyPass]
        {
            assert!(!adapter_for(mode).requires_client_certificate(), "{mode:?} must NOT require a client certificate",);
        }
        assert!(
            adapter_for(MasqueAuthMode::CloudflareMtls).requires_client_certificate(),
            "cloudflare_mtls adapter must require a client certificate",
        );
    }

    #[test]
    fn only_cloudflare_adapter_wants_geohash_header() {
        for mode in
            [MasqueAuthMode::None, MasqueAuthMode::Bearer, MasqueAuthMode::Preshared, MasqueAuthMode::PrivacyPass]
        {
            assert!(!adapter_for(mode).wants_geohash_header(), "{mode:?} must NOT want the geohash header");
        }
        assert!(
            adapter_for(MasqueAuthMode::CloudflareMtls).wants_geohash_header(),
            "cloudflare_mtls adapter must want the geohash header",
        );
    }
}
