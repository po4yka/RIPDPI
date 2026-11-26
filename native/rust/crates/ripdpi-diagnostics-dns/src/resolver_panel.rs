use std::net::IpAddr;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublicResolver {
    pub name: &'static str,
    pub udp_addr: IpAddr,
    pub doh_url: &'static str,
    pub ipv6_only: bool,
}

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolverReachability {
    Reachable,
    Degraded,
    Blocked,
    UniversalFailure,
}

#[derive(Debug, Clone)]
pub struct ResolverSurveyResult {
    pub resolver: &'static str,
    pub udp_ok: bool,
    pub doh_ok: bool,
    pub median_latency_ms_udp: Option<u32>,
    pub median_latency_ms_doh: Option<u32>,
    pub verdict: ResolverReachability,
}

pub fn classify_resolver(udp_ok: bool, doh_ok: bool) -> ResolverReachability {
    match (udp_ok, doh_ok) {
        (true, true) => ResolverReachability::Reachable,
        (false, false) => ResolverReachability::Blocked,
        _ => ResolverReachability::Degraded,
    }
}

#[derive(Debug, Clone, Default)]
pub struct ResolverSurveyOptions {
    pub ipv4_only: bool,
}

pub struct ResolverSurveyRunner {
    panel: Vec<&'static PublicResolver>,
}

impl ResolverSurveyRunner {
    pub fn new(options: ResolverSurveyOptions) -> Self {
        let panel = RESOLVER_PANEL.iter().filter(|r| !options.ipv4_only || !r.ipv6_only).collect();
        Self { panel }
    }

    pub fn survey<F>(&self, probe_fn: F) -> Vec<ResolverSurveyResult>
    where
        F: Fn(&PublicResolver) -> (bool, bool, Option<u32>, Option<u32>),
    {
        let mut results: Vec<ResolverSurveyResult> = self
            .panel
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
            .collect();
        if results.len() > 1 && results.iter().all(|r| r.verdict == ResolverReachability::Blocked) {
            for result in &mut results {
                result.verdict = ResolverReachability::UniversalFailure;
            }
        }
        results
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn panel_contains_required_providers() {
        let names: Vec<&str> = RESOLVER_PANEL.iter().map(|r| r.name).collect();
        assert!(names.contains(&"Google"), "Google missing from panel");
        assert!(names.contains(&"Cloudflare"), "Cloudflare missing from panel");
        assert!(names.contains(&"Quad9"), "Quad9 missing from panel");
        assert!(names.len() >= 10, "panel must have at least 10 entries, got {}", names.len());
    }

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
        let results = runner.survey(|r| {
            if r.name == "Cloudflare" { (false, false, None, None) } else { (true, true, Some(15), Some(25)) }
        });

        let cloudflare = results.iter().find(|r| r.resolver == "Cloudflare").unwrap();
        assert_eq!(cloudflare.verdict, ResolverReachability::Blocked);
        assert!(!cloudflare.udp_ok);
        assert!(!cloudflare.doh_ok);

        for r in results.iter().filter(|r| r.resolver != "Cloudflare") {
            assert_eq!(r.verdict, ResolverReachability::Reachable, "{} should be Reachable", r.resolver);
        }
    }

    #[test]
    fn survey_where_every_resolver_fails_is_universal_failure_not_blocked() {
        let runner = ResolverSurveyRunner::new(ResolverSurveyOptions::default());
        let results = runner.survey(|_| (false, false, None, None));
        assert!(results.len() > 1);
        assert!(results.iter().all(|r| r.verdict == ResolverReachability::UniversalFailure));
    }

    #[test]
    fn survey_with_one_working_resolver_keeps_per_resolver_blocked_verdicts() {
        let runner = ResolverSurveyRunner::new(ResolverSurveyOptions::default());
        let results =
            runner.survey(|r| if r.name == "Google" { (true, true, None, None) } else { (false, false, None, None) });
        for r in &results {
            let expected =
                if r.resolver == "Google" { ResolverReachability::Reachable } else { ResolverReachability::Blocked };
            assert_eq!(r.verdict, expected, "{}", r.resolver);
        }
    }

    #[test]
    fn ipv4_only_filters_ipv6_only_resolvers() {
        let all_runner = ResolverSurveyRunner::new(ResolverSurveyOptions { ipv4_only: false });
        let v4_runner = ResolverSurveyRunner::new(ResolverSurveyOptions { ipv4_only: true });

        assert_eq!(all_runner.panel.len(), v4_runner.panel.len());

        for r in &v4_runner.panel {
            assert!(!r.ipv6_only, "{} is IPv6-only but appeared in ipv4_only panel", r.name);
        }
    }
}
