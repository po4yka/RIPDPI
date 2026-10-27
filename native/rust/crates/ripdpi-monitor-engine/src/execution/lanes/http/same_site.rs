/// Same-site rule of the runtime redirect trigger, applied to the registrable domain of the
/// requested host: a sibling such as `www.google.com` -> `consent.google.com` stays on the site, and
/// so does a relative Location.
pub(super) fn redirect_leaves_site(host: &str, location: &str) -> bool {
    let site = registrable_domain(host);
    let request = format!("GET / HTTP/1.1\r\nHost: {site}\r\n\r\n");
    let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\n\r\n");
    ripdpi_packets::is_http_redirect(request.as_bytes(), response.as_bytes())
}

/// shortcut: the last two labels, or three under a two-letter TLD with a generic second level
/// (`bbc.co.uk`); upgrade to a public suffix list if hosted-platform siblings (`*.github.io`) matter.
fn registrable_domain(host: &str) -> &str {
    let generic_second_level = |label: &str| matches!(label, "co" | "com" | "net" | "org" | "gov" | "edu" | "ac");
    let keep = match host.rsplit('.').collect::<Vec<_>>().as_slice() {
        _ if host.parse::<std::net::IpAddr>().is_ok() => return host,
        [tld, second, _, ..] if tld.len() == 2 && generic_second_level(second) => 3,
        _ => 2,
    };
    host.rmatch_indices('.').nth(keep - 1).map_or(host, |(dot, _)| &host[dot + 1..])
}
