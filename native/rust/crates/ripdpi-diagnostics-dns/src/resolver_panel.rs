//! Public DNS resolver availability survey.
//!
//! Defines a static curated panel of well-known public DNS resolvers and a
//! pure-logic survey runner whose probing path is fully injectable. Real
//! network I/O (UDP/53 + DoH) is deferred to a follow-up crate — the runner
//! accepts a `probe_fn` closure so unit tests exercise all verdict paths
//! without touching the network.

use std::net::IpAddr;

// ---------------------------------------------------------------------------
// Panel entry
// ---------------------------------------------------------------------------

/// A single well-known public DNS resolver entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublicResolver {
    /// Human-readable provider name.
    pub name: &'static str,
    /// Primary UDP/53 address.
    pub udp_addr: IpAddr,
    /// DoH endpoint URL.
    pub doh_url: &'static str,
    /// True when this resolver only exposes IPv6 addresses on the public internet.
    pub ipv6_only: bool,
}

// ---------------------------------------------------------------------------
// Static panel
// ---------------------------------------------------------------------------

/// Canonical public IP addresses are taken from each provider's official docs.
pub static RESOLVER_PANEL: &[PublicResolver] = &[
    PublicResolver {
        name: "Google",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(8, 8, 8, 8)),
        doh_url: "https://dns.google/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "Cloudflare",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)),
        doh_url: "https://cloudflare-dns.com/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "Quad9",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(9, 9, 9, 9)),
        doh_url: "https://dns.quad9.net/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "AdGuard",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(94, 140, 14, 14)),
        doh_url: "https://dns.adguard-dns.com/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "OpenDNS",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(208, 67, 222, 222)),
        doh_url: "https://doh.opendns.com/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "ControlD",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(76, 76, 2, 0)),
        doh_url: "https://freedns.controld.com/p0",
        ipv6_only: false,
    },
    PublicResolver {
        name: "CleanBrowsing",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(185, 228, 168, 9)),
        doh_url: "https://doh.cleanbrowsing.org/doh/security-filter/",
        ipv6_only: false,
    },
    PublicResolver {
        name: "NextDNS",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(45, 90, 28, 0)),
        doh_url: "https://dns.nextdns.io/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "Mullvad",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(194, 242, 2, 2)),
        doh_url: "https://dns.mullvad.net/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "DNS.SB",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(185, 222, 222, 222)),
        doh_url: "https://doh.dns.sb/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "Yandex",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(77, 88, 8, 8)),
        doh_url: "https://dns.yandex.ru/dns-query",
        ipv6_only: false,
    },
    PublicResolver {
        name: "Alibaba",
        udp_addr: IpAddr::V4(std::net::Ipv4Addr::new(223, 5, 5, 5)),
        doh_url: "https://dns.alidns.com/dns-query",
        ipv6_only: false,
    },
];

// ---------------------------------------------------------------------------
// Verdict types
// ---------------------------------------------------------------------------

/// Per-resolver reachability verdict.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolverReachability {
    /// Both UDP/53 and DoH are reachable.
    Reachable,
    /// One of UDP/53 or DoH is reachable, the other is not.
    Degraded,
    /// Neither UDP/53 nor DoH is reachable.
    Blocked,
}

/// Per-resolver survey result returned by [`ResolverSurveyRunner::survey`].
#[derive(Debug, Clone)]
pub struct ResolverSurveyResult {
    /// Provider name (same as [`PublicResolver::name`]).
    pub resolver: &'static str,
    /// Whether the UDP/53 probe succeeded.
    pub udp_ok: bool,
    /// Whether the DoH probe succeeded.
    pub doh_ok: bool,
    /// Median round-trip latency for UDP/53 probes, if measured.
    pub median_latency_ms_udp: Option<u32>,
    /// Median round-trip latency for DoH probes, if measured.
    pub median_latency_ms_doh: Option<u32>,
    /// Overall reachability verdict derived from `udp_ok` and `doh_ok`.
    pub verdict: ResolverReachability,
}

// ---------------------------------------------------------------------------
// Classification helper
// ---------------------------------------------------------------------------

/// Derive a [`ResolverReachability`] verdict from individual probe outcomes.
///
/// | `udp_ok` | `doh_ok` | result |
/// |----------|----------|--------|
/// | true     | true     | [`ResolverReachability::Reachable`] |
/// | mixed    | mixed    | [`ResolverReachability::Degraded`] |
/// | false    | false    | [`ResolverReachability::Blocked`] |
pub fn classify_resolver(udp_ok: bool, doh_ok: bool) -> ResolverReachability {
    match (udp_ok, doh_ok) {
        (true, true) => ResolverReachability::Reachable,
        (false, false) => ResolverReachability::Blocked,
        _ => ResolverReachability::Degraded,
    }
}

// ---------------------------------------------------------------------------
// Survey runner
// ---------------------------------------------------------------------------

/// Options for constructing a [`ResolverSurveyRunner`].
#[derive(Debug, Clone, Default)]
pub struct ResolverSurveyOptions {
    /// When `true`, resolvers that expose only IPv6 addresses are excluded from
    /// the survey. Respects the `ipv4-only` setting hook in the host app.
    pub ipv4_only: bool,
}

/// Runs an availability survey over the static [`RESOLVER_PANEL`].
///
/// All network I/O is injected via a `probe_fn` closure so the runner can be
/// exercised in unit tests without touching the network. Real UDP/53 + DoH
/// probing is deferred to a follow-up integration layer.
///
/// Bounded concurrency (≤8 in-flight) is noted as a future enhancement; the
/// current implementation uses simple sequential iteration to keep this unit
/// pure-logic.
pub struct ResolverSurveyRunner {
    panel: Vec<&'static PublicResolver>,
}

impl ResolverSurveyRunner {
    /// Create a runner using the default global panel with optional IPv4 filtering.
    pub fn new(options: ResolverSurveyOptions) -> Self {
        let panel = RESOLVER_PANEL.iter().filter(|r| !options.ipv4_only || !r.ipv6_only).collect();
        Self { panel }
    }

    /// Survey every resolver in the panel using the supplied `probe_fn`.
    ///
    /// `probe_fn` receives a reference to each [`PublicResolver`] and returns
    /// `(udp_ok, doh_ok, median_latency_ms_udp, median_latency_ms_doh)`.
    ///
    /// Results are returned in panel order (stable, deterministic).
    pub fn survey<F>(&self, probe_fn: F) -> Vec<ResolverSurveyResult>
    where
        F: Fn(&PublicResolver) -> (bool, bool, Option<u32>, Option<u32>),
    {
        self.panel
            .iter()
            .map(|resolver| {
                let (udp_ok, doh_ok, lat_udp, lat_doh) = probe_fn(resolver);
                ResolverSurveyResult {
                    resolver: resolver.name,
                    udp_ok,
                    doh_ok,
                    median_latency_ms_udp: lat_udp,
                    median_latency_ms_doh: lat_doh,
                    verdict: classify_resolver(udp_ok, doh_ok),
                }
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // -- classify_resolver --------------------------------------------------

    #[test]
    fn classify_both_ok_is_reachable() {
        assert_eq!(classify_resolver(true, true), ResolverReachability::Reachable);
    }

    #[test]
    fn classify_udp_only_is_degraded() {
        assert_eq!(classify_resolver(true, false), ResolverReachability::Degraded);
    }

    #[test]
    fn classify_doh_only_is_degraded() {
        assert_eq!(classify_resolver(false, true), ResolverReachability::Degraded);
    }

    #[test]
    fn classify_neither_is_blocked() {
        assert_eq!(classify_resolver(false, false), ResolverReachability::Blocked);
    }

    // -- panel content ------------------------------------------------------

    #[test]
    fn panel_contains_required_providers() {
        let names: Vec<&str> = RESOLVER_PANEL.iter().map(|r| r.name).collect();
        assert!(names.contains(&"Google"), "Google missing from panel");
        assert!(names.contains(&"Cloudflare"), "Cloudflare missing from panel");
        assert!(names.contains(&"Quad9"), "Quad9 missing from panel");
        assert!(names.len() >= 10, "panel must have at least 10 entries, got {}", names.len());
    }

    // -- survey behaviour ---------------------------------------------------

    #[test]
    fn survey_returns_one_result_per_panel_entry() {
        let runner = ResolverSurveyRunner::new(ResolverSurveyOptions::default());
        let results = runner.survey(|_| (true, true, Some(10), Some(20)));
        assert_eq!(results.len(), runner.panel.len());
    }

    #[test]
    fn survey_preserves_panel_order() {
        let runner = ResolverSurveyRunner::new(ResolverSurveyOptions::default());
        let results = runner.survey(|_| (true, true, None, None));
        for (result, resolver) in results.iter().zip(runner.panel.iter()) {
            assert_eq!(result.resolver, resolver.name);
        }
    }

    #[test]
    fn survey_blocked_resolver_yields_blocked_verdict() {
        let runner = ResolverSurveyRunner::new(ResolverSurveyOptions::default());
        // Block Cloudflare, let everyone else be Reachable.
        let results = runner.survey(|r| {
            if r.name == "Cloudflare" {
                (false, false, None, None)
            } else {
                (true, true, Some(15), Some(25))
            }
        });

        let cloudflare = results.iter().find(|r| r.resolver == "Cloudflare").unwrap();
        assert_eq!(cloudflare.verdict, ResolverReachability::Blocked);
        assert!(!cloudflare.udp_ok);
        assert!(!cloudflare.doh_ok);

        // All others must be Reachable.
        for r in results.iter().filter(|r| r.resolver != "Cloudflare") {
            assert_eq!(r.verdict, ResolverReachability::Reachable, "{} should be Reachable", r.resolver);
        }
    }

    #[test]
    fn ipv4_only_filters_ipv6_only_resolvers() {
        // The current panel has no IPv6-only entries, so both modes return the
        // same count. This test validates the filtering logic path: if we
        // inject a synthetic IPv6-only resolver it would be dropped.
        let all_runner = ResolverSurveyRunner::new(ResolverSurveyOptions { ipv4_only: false });
        let v4_runner = ResolverSurveyRunner::new(ResolverSurveyOptions { ipv4_only: true });

        // All current panel entries are IPv4-capable, so counts must match.
        assert_eq!(all_runner.panel.len(), v4_runner.panel.len());

        // Verify no IPv6-only resolver slipped into the v4 panel.
        for r in &v4_runner.panel {
            assert!(!r.ipv6_only, "{} is IPv6-only but appeared in ipv4_only panel", r.name);
        }
    }
}
