use std::net::IpAddr;

use ipnet::IpNet;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FilterSet {
    pub hosts: Vec<String>,
    pub ipset: Vec<IpNet>,
    pub geoip_countries: Vec<String>,
    pub geosite_categories: Vec<String>,
}

pub(crate) fn common_suffix_match(host: &str, rule: &str) -> bool {
    host == rule || host.strip_suffix(rule).is_some_and(|prefix| prefix.ends_with('.'))
}

impl FilterSet {
    pub fn hosts_match(&self, host: &str) -> bool {
        self.hosts.iter().any(|rule| common_suffix_match(host, rule))
    }

    pub fn host_filters_empty(&self) -> bool {
        self.hosts.is_empty() && self.geosite_categories.is_empty()
    }

    pub fn ipset_match(&self, ip: IpAddr) -> bool {
        self.ipset.iter().any(|rule| rule.contains(&ip))
    }

    pub fn ip_filters_empty(&self) -> bool {
        self.ipset.is_empty() && self.geoip_countries.is_empty()
    }
}
