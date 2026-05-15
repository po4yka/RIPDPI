//! Rule-based traffic routing matcher for RIPDPI.
//!
//! Evaluates a list of rules in first-match-wins order and produces an
//! [`OutboundAction`] for each incoming flow.

#![forbid(unsafe_code)]

use std::collections::HashSet;
use std::net::IpAddr;

use ipnet::IpNet;
use regex::Regex;

// ──────────────────────────────────────────────────────────────────────────────
// Public types
// ──────────────────────────────────────────────────────────────────────────────

/// The action the matcher assigns to a flow.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OutboundAction {
    /// Route through the named/default proxy.
    Proxy,
    /// Send directly (bypass proxy).
    Bypass,
    /// Drop the flow.
    Block,
    /// Route through a specific profile ID.
    Profile(u64),
    /// Route through a named group ID.
    Group(u64),
}

/// Layer-4 protocol selector.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Network {
    Tcp,
    Udp,
    Both,
}

/// A single port or inclusive port range.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PortPattern {
    Single(u16),
    Range(u16, u16),
}

impl PortPattern {
    /// Returns `true` when `port` falls within this pattern.
    pub fn matches(&self, port: u16) -> bool {
        match self {
            Self::Single(p) => *p == port,
            Self::Range(lo, hi) => port >= *lo && port <= *hi,
        }
    }
}

/// A domain-matching pattern, produced by parsing domain strings.
#[derive(Debug)]
pub enum DomainPattern {
    /// Exact hostname match.
    Exact(String),
    /// The stored string is the apex; `api.example.com` matches `.example.com`.
    Suffix(String),
    /// Full regex match.
    Regex(Regex),
    /// GeoSite category placeholder — always returns `false` until implemented.
    GeoSite(String),
}

impl DomainPattern {
    /// Parse a raw domain string into a [`DomainPattern`].
    ///
    /// Prefixes understood:
    /// - `domain:foo.com`         → [`DomainPattern::Exact`]
    /// - `domain_suffix:.foo.com` → [`DomainPattern::Suffix`]
    /// - `domain_regex:…`         → [`DomainPattern::Regex`]
    /// - `geosite:…`              → [`DomainPattern::GeoSite`]
    /// - bare string              → [`DomainPattern::Exact`]
    pub fn parse(raw: &str) -> Result<Self, MatcherError> {
        if let Some(rest) = raw.strip_prefix("domain_suffix:") {
            // Normalise: ensure the apex has a leading dot so matching is unambiguous.
            let apex = if rest.starts_with('.') { rest.to_owned() } else { format!(".{rest}") };
            return Ok(Self::Suffix(apex));
        }
        if let Some(rest) = raw.strip_prefix("domain_regex:") {
            let re = Regex::new(rest).map_err(|e| MatcherError::InvalidRegex(e.to_string()))?;
            return Ok(Self::Regex(re));
        }
        if let Some(rest) = raw.strip_prefix("geosite:") {
            return Ok(Self::GeoSite(rest.to_owned()));
        }
        // `domain:` prefix or bare string → exact.
        let bare = raw.strip_prefix("domain:").unwrap_or(raw);
        Ok(Self::Exact(bare.to_owned()))
    }

    /// Returns `true` when `hostname` matches this pattern.
    pub fn matches(&self, hostname: &str) -> bool {
        match self {
            Self::Exact(s) => s.eq_ignore_ascii_case(hostname),
            Self::Suffix(apex) => {
                // `apex` is stored with a leading dot, e.g. `.example.com`.
                // `hostname` matches when:
                //   (a) it equals the apex without the leading dot  (`example.com`)
                //   (b) it ends with the apex as-is                  (`api.example.com`)
                let h = hostname.to_ascii_lowercase();
                let a = apex.to_ascii_lowercase(); // a starts with '.'
                let bare = &a[1..]; // strip leading dot → `example.com`
                h == bare || h.ends_with(&a)
            }
            Self::Regex(re) => re.is_match(hostname),
            Self::GeoSite(_) => false, // deferred
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Suffix trie
// ──────────────────────────────────────────────────────────────────────────────

/// A compact trie keyed on reversed domain labels for fast suffix matching.
///
/// Insert `example.com` to match any subdomain of `example.com`.
#[derive(Default)]
pub struct DomainTrie {
    children: Vec<(String, DomainTrie)>,
    /// `true` means this node is a terminal — a domain ending here matches.
    terminal: bool,
}

impl DomainTrie {
    /// Create an empty trie.
    pub fn new() -> Self {
        Self::default()
    }

    /// Insert an apex domain.  The trie will match any subdomain of `apex`.
    ///
    /// `apex` should be a plain domain like `example.com` (no leading dot).
    /// Matching is case-insensitive; the apex is normalised to lowercase on insert.
    pub fn insert(&mut self, apex: &str) {
        let labels: Vec<String> = apex.to_ascii_lowercase().split('.').rev().map(str::to_owned).collect();
        self.insert_labels(&labels);
    }

    fn insert_labels(&mut self, labels: &[String]) {
        if labels.is_empty() {
            self.terminal = true;
            return;
        }
        let label = &labels[0];
        // Find or create child.
        let pos = self.children.iter().position(|(l, _)| l == label);
        let idx = match pos {
            Some(i) => i,
            None => {
                self.children.push((label.clone(), DomainTrie::default()));
                self.children.len() - 1
            }
        };
        self.children[idx].1.insert_labels(&labels[1..]);
    }

    /// Returns `true` if `hostname` is a subdomain of any inserted apex.
    pub fn matches(&self, hostname: &str) -> bool {
        let labels: Vec<String> = hostname.to_ascii_lowercase().split('.').rev().map(str::to_owned).collect();
        self.match_labels(&labels)
    }

    fn match_labels(&self, labels: &[String]) -> bool {
        if self.terminal {
            return true;
        }
        if labels.is_empty() {
            return false;
        }
        let label = &labels[0];
        for (l, child) in &self.children {
            if l == label {
                return child.match_labels(&labels[1..]);
            }
        }
        false
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Flow descriptor
// ──────────────────────────────────────────────────────────────────────────────

/// Describes a single network flow to be dispatched.
#[derive(Debug, Default)]
pub struct Flow {
    /// Destination hostname (optional; mutually exclusive with `dst_ip`).
    pub dst_domain: Option<String>,
    /// Destination IP address.
    pub dst_ip: Option<IpAddr>,
    /// Destination port.
    pub dst_port: Option<u16>,
    /// Source port.
    pub src_port: Option<u16>,
    /// Layer-4 protocol.
    pub network: Option<Network>,
    /// Process name (e.g. `"com.example.app"`).
    pub process_name: Option<String>,
    /// Android UID of the originating package.
    pub package_uid: Option<u32>,
}

// ──────────────────────────────────────────────────────────────────────────────
// Rule
// ──────────────────────────────────────────────────────────────────────────────

/// A single routing rule.  All populated matchers must match simultaneously
/// (boolean AND); an empty matcher list means "match everything".
pub struct Rule {
    pub domains: Vec<DomainPattern>,
    pub ip_cidrs: Vec<IpNet>,
    pub ports: Vec<PortPattern>,
    pub source_ports: Vec<PortPattern>,
    pub network: Network,
    pub process_name: Option<String>,
    /// Set of Android UIDs for the package matcher.
    pub packages: HashSet<u32>,
    pub outbound_action: OutboundAction,
    pub enabled: bool,
}

impl Rule {
    /// Returns `true` when this rule matches `flow`.
    ///
    /// Each populated field is a conjunctive constraint; an empty `Vec` / `None`
    /// counts as "wildcard" (matches anything).
    pub fn matches(&self, flow: &Flow) -> bool {
        if !self.enabled {
            return false;
        }

        // Domain matcher.
        if !self.domains.is_empty() {
            match &flow.dst_domain {
                Some(host) => {
                    if !self.domains.iter().any(|p| p.matches(host)) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // IP CIDR matcher.
        if !self.ip_cidrs.is_empty() {
            match &flow.dst_ip {
                Some(ip) => {
                    if !self.ip_cidrs.iter().any(|net| net.contains(ip)) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Destination port matcher.
        if !self.ports.is_empty() {
            match flow.dst_port {
                Some(port) => {
                    if !self.ports.iter().any(|p| p.matches(port)) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Source port matcher.
        if !self.source_ports.is_empty() {
            match flow.src_port {
                Some(port) => {
                    if !self.source_ports.iter().any(|p| p.matches(port)) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Network (protocol) matcher.
        match (&self.network, &flow.network) {
            (Network::Both, _) => {}
            (_, None) => {}
            (rule_net, Some(flow_net)) => {
                if rule_net != flow_net {
                    return false;
                }
            }
        }

        // Process-name matcher.
        if let Some(ref name) = self.process_name {
            match &flow.process_name {
                Some(proc) => {
                    if proc != name {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Package UID matcher.
        if !self.packages.is_empty() {
            match flow.package_uid {
                Some(uid) => {
                    if !self.packages.contains(&uid) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        true
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// RuleMatcher
// ──────────────────────────────────────────────────────────────────────────────

/// Holds a list of rules and dispatches flows against them in first-match-wins
/// order, falling back to a configurable default action.
pub struct RuleMatcher {
    rules: Vec<Rule>,
    default_action: OutboundAction,
}

impl RuleMatcher {
    /// Create a new matcher with the given rules and a fallback default action.
    pub fn new(rules: Vec<Rule>, default_action: OutboundAction) -> Self {
        Self { rules, default_action }
    }

    /// Dispatch `flow` against the rule list and return the matching action.
    ///
    /// Rules are evaluated in order; the first enabled rule whose matchers all
    /// pass wins.  If no rule matches the `default_action` is returned.
    pub fn dispatch(&self, flow: &Flow) -> OutboundAction {
        for rule in &self.rules {
            if rule.matches(flow) {
                return rule.outbound_action.clone();
            }
        }
        self.default_action.clone()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Error type
// ──────────────────────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error)]
pub enum MatcherError {
    #[error("invalid CIDR: {0}")]
    InvalidCidr(String),
    #[error("invalid regex: {0}")]
    InvalidRegex(String),
}

// ──────────────────────────────────────────────────────────────────────────────
// Tests (written first — TDD)
// ──────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use std::collections::HashSet;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    use ipnet::{IpNet, Ipv4Net, Ipv6Net};

    use super::{
        DomainPattern, DomainTrie, Flow, MatcherError, Network, OutboundAction, PortPattern, Rule, RuleMatcher,
    };

    // ── helpers ────────────────────────────────────────────────────────────────

    fn bypass_lan_rule() -> Rule {
        Rule {
            domains: vec![],
            ip_cidrs: vec![
                "10.0.0.0/8".parse::<IpNet>().unwrap(),
                "192.168.0.0/16".parse::<IpNet>().unwrap(),
                "172.16.0.0/12".parse::<IpNet>().unwrap(),
            ],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Bypass,
            enabled: true,
        }
    }

    fn proxy_all_rule() -> Rule {
        Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Proxy,
            enabled: true,
        }
    }

    // ── first-match-wins ───────────────────────────────────────────────────────

    #[test]
    fn first_match_wins_lan_bypass() {
        let matcher = RuleMatcher::new(vec![bypass_lan_rule(), proxy_all_rule()], OutboundAction::Proxy);

        let lan_flow = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 1))), ..Default::default() };
        assert_eq!(matcher.dispatch(&lan_flow), OutboundAction::Bypass);
    }

    #[test]
    fn first_match_wins_non_lan_proxied() {
        let matcher = RuleMatcher::new(vec![bypass_lan_rule(), proxy_all_rule()], OutboundAction::Block);

        let public_flow = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8))), ..Default::default() };
        assert_eq!(matcher.dispatch(&public_flow), OutboundAction::Proxy);
    }

    // ── disabled rules skipped ─────────────────────────────────────────────────

    #[test]
    fn disabled_rule_is_skipped() {
        let mut lan = bypass_lan_rule();
        lan.enabled = false;

        let matcher = RuleMatcher::new(vec![lan, proxy_all_rule()], OutboundAction::Block);

        let lan_flow = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1))), ..Default::default() };
        // The LAN rule is disabled so proxy_all_rule should fire instead.
        assert_eq!(matcher.dispatch(&lan_flow), OutboundAction::Proxy);
    }

    // ── default action ─────────────────────────────────────────────────────────

    #[test]
    fn default_action_when_no_rule_matches() {
        let matcher = RuleMatcher::new(vec![bypass_lan_rule()], OutboundAction::Block);

        let public_flow = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1))), ..Default::default() };
        assert_eq!(matcher.dispatch(&public_flow), OutboundAction::Block);
    }

    #[test]
    fn default_proxy_when_no_rules() {
        let matcher = RuleMatcher::new(vec![], OutboundAction::Proxy);
        assert_eq!(matcher.dispatch(&Flow::default()), OutboundAction::Proxy);
    }

    // ── domain suffix trie ─────────────────────────────────────────────────────

    #[test]
    fn trie_matches_subdomain() {
        let mut trie = DomainTrie::new();
        trie.insert("example.com");

        assert!(trie.matches("api.example.com"), "subdomain should match");
        assert!(trie.matches("example.com"), "apex itself should match");
    }

    #[test]
    fn trie_does_not_match_sibling_apex() {
        let mut trie = DomainTrie::new();
        trie.insert("example.com");

        assert!(!trie.matches("example.com.attacker.com"), "attacker domain must not match");
        assert!(!trie.matches("notexample.com"), "unrelated domain must not match");
    }

    #[test]
    fn trie_case_insensitive() {
        let mut trie = DomainTrie::new();
        trie.insert("Example.COM");
        assert!(trie.matches("API.example.com"));
    }

    // ── DomainPattern suffix ───────────────────────────────────────────────────

    #[test]
    fn domain_suffix_pattern_matches() {
        let p = DomainPattern::parse("domain_suffix:example.com").unwrap();
        assert!(p.matches("api.example.com"));
        assert!(p.matches("example.com"));
        assert!(!p.matches("example.com.evil.com"));
        assert!(!p.matches("notexample.com"));
    }

    #[test]
    fn domain_exact_pattern() {
        let p = DomainPattern::parse("domain:example.com").unwrap();
        assert!(p.matches("example.com"));
        assert!(!p.matches("api.example.com"));
    }

    #[test]
    fn domain_regex_pattern() {
        let p = DomainPattern::parse("domain_regex:^api\\..*\\.com$").unwrap();
        assert!(p.matches("api.example.com"));
        assert!(!p.matches("www.example.com"));
    }

    #[test]
    fn domain_geosite_always_false() {
        let p = DomainPattern::parse("geosite:cn").unwrap();
        assert!(!p.matches("baidu.com"), "geosite is a placeholder and must return false");
    }

    #[test]
    fn domain_pattern_invalid_regex() {
        let result = DomainPattern::parse("domain_regex:[invalid");
        assert!(matches!(result, Err(MatcherError::InvalidRegex(_))));
    }

    // ── IP CIDR v4 ─────────────────────────────────────────────────────────────

    #[test]
    fn ip_cidr_v4_match() {
        let net: IpNet = "192.168.0.0/16".parse().unwrap();
        assert!(net.contains(&IpAddr::V4(Ipv4Addr::new(192, 168, 100, 1))));
        assert!(!net.contains(&IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1))));
    }

    #[test]
    fn rule_ip_cidr_v4() {
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec!["192.168.0.0/24".parse::<IpNet>().unwrap()],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Bypass,
            enabled: true,
        };
        let flow_in = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(192, 168, 0, 50))), ..Default::default() };
        let flow_out = Flow { dst_ip: Some(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 50))), ..Default::default() };
        assert!(rule.matches(&flow_in));
        assert!(!rule.matches(&flow_out));
    }

    // ── IP CIDR v6 ─────────────────────────────────────────────────────────────

    #[test]
    fn rule_ip_cidr_v6() {
        let net: Ipv6Net = "fc00::/7".parse().unwrap();
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![IpNet::V6(net)],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Bypass,
            enabled: true,
        };
        // fd00::/8 is within fc00::/7
        let flow_in = Flow { dst_ip: Some(IpAddr::V6("fd00::1".parse::<Ipv6Addr>().unwrap())), ..Default::default() };
        let flow_out =
            Flow { dst_ip: Some(IpAddr::V6("2001:db8::1".parse::<Ipv6Addr>().unwrap())), ..Default::default() };
        assert!(rule.matches(&flow_in));
        assert!(!rule.matches(&flow_out));
    }

    // ── port range ─────────────────────────────────────────────────────────────

    #[test]
    fn port_range_matcher() {
        let p = PortPattern::Range(80, 90);
        assert!(p.matches(80));
        assert!(p.matches(85));
        assert!(p.matches(90));
        assert!(!p.matches(79));
        assert!(!p.matches(91));
    }

    #[test]
    fn rule_port_range() {
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![PortPattern::Range(80, 90)],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Block,
            enabled: true,
        };
        let hit = Flow { dst_port: Some(80), ..Default::default() };
        let miss = Flow { dst_port: Some(443), ..Default::default() };
        assert!(rule.matches(&hit));
        assert!(!rule.matches(&miss));
    }

    // ── package uid ────────────────────────────────────────────────────────────

    #[test]
    fn package_uid_matcher() {
        let mut pkgs = HashSet::new();
        pkgs.insert(10_100_u32);
        pkgs.insert(10_200_u32);

        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: pkgs,
            outbound_action: OutboundAction::Proxy,
            enabled: true,
        };
        let hit = Flow { package_uid: Some(10_100), ..Default::default() };
        let miss = Flow { package_uid: Some(10_999), ..Default::default() };
        let no_uid = Flow { package_uid: None, ..Default::default() };

        assert!(rule.matches(&hit));
        assert!(!rule.matches(&miss));
        assert!(!rule.matches(&no_uid));
    }

    // ── profile / group actions ────────────────────────────────────────────────

    #[test]
    fn profile_and_group_actions() {
        let r_profile = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![PortPattern::Single(443)],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Profile(42),
            enabled: true,
        };
        let r_group = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![PortPattern::Single(80)],
            source_ports: vec![],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Group(7),
            enabled: true,
        };
        let matcher = RuleMatcher::new(vec![r_profile, r_group], OutboundAction::Block);

        let f443 = Flow { dst_port: Some(443), ..Default::default() };
        let f80 = Flow { dst_port: Some(80), ..Default::default() };

        assert_eq!(matcher.dispatch(&f443), OutboundAction::Profile(42));
        assert_eq!(matcher.dispatch(&f80), OutboundAction::Group(7));
    }

    // ── source ports ──────────────────────────────────────────────────────────

    #[test]
    fn source_port_matcher() {
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![],
            source_ports: vec![PortPattern::Single(12345)],
            network: Network::Both,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Bypass,
            enabled: true,
        };
        let hit = Flow { src_port: Some(12345), ..Default::default() };
        let miss = Flow { src_port: Some(54321), ..Default::default() };
        assert!(rule.matches(&hit));
        assert!(!rule.matches(&miss));
    }

    // ── network protocol filter ────────────────────────────────────────────────

    #[test]
    fn network_tcp_only_skips_udp() {
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![],
            source_ports: vec![],
            network: Network::Tcp,
            process_name: None,
            packages: HashSet::new(),
            outbound_action: OutboundAction::Proxy,
            enabled: true,
        };
        let tcp = Flow { network: Some(Network::Tcp), ..Default::default() };
        let udp = Flow { network: Some(Network::Udp), ..Default::default() };
        let unspec = Flow { network: None, ..Default::default() };

        assert!(rule.matches(&tcp));
        assert!(!rule.matches(&udp));
        assert!(rule.matches(&unspec), "unspecified network is a wildcard");
    }

    // ── process name matcher ──────────────────────────────────────────────────

    #[test]
    fn process_name_matcher() {
        let rule = Rule {
            domains: vec![],
            ip_cidrs: vec![],
            ports: vec![],
            source_ports: vec![],
            network: Network::Both,
            process_name: Some("com.example.app".to_owned()),
            packages: HashSet::new(),
            outbound_action: OutboundAction::Proxy,
            enabled: true,
        };
        let hit = Flow { process_name: Some("com.example.app".to_owned()), ..Default::default() };
        let miss = Flow { process_name: Some("com.other.app".to_owned()), ..Default::default() };
        assert!(rule.matches(&hit));
        assert!(!rule.matches(&miss));
    }

    // ── IPv4Net direct construction ───────────────────────────────────────────

    #[test]
    fn ipv4net_direct_construction() {
        let net: Ipv4Net = "10.0.0.0/8".parse().unwrap();
        let ip = IpAddr::V4(Ipv4Addr::new(10, 255, 255, 255));
        assert!(IpNet::V4(net).contains(&ip));
    }
}
