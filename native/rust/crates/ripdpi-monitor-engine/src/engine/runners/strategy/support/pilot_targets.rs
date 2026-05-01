use crate::types::DomainTarget;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum PilotHostingFamily {
    Direct,
    Cloudflare,
    Google,
    DomesticCdn,
    ForeignCdn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum PilotReachabilitySet {
    Control,
    Domestic,
    Foreign,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct PilotTargetBucket {
    hosting_family: PilotHostingFamily,
    reachability_set: PilotReachabilitySet,
    ech_likely: bool,
}

fn pilot_hosting_family(host: &str) -> PilotHostingFamily {
    let host = host.trim().to_ascii_lowercase();
    if host.ends_with(".workers.dev")
        || host.ends_with(".pages.dev")
        || host.contains("cloudflare")
        || host.ends_with(".cloudflare.com")
    {
        PilotHostingFamily::Cloudflare
    } else if host.ends_with(".google.com")
        || host.ends_with(".googlevideo.com")
        || host.ends_with(".googleapis.com")
        || host.ends_with(".gstatic.com")
        || host.ends_with(".youtube.com")
        || host.ends_with(".ytimg.com")
        || host.ends_with(".1e100.net")
    {
        PilotHostingFamily::Google
    } else if host.ends_with(".yandex.ru")
        || host.ends_with(".yandex.net")
        || host.ends_with(".ya.ru")
        || host.ends_with(".vk.com")
        || host.ends_with(".mail.ru")
        || host.ends_with(".ok.ru")
        || host.ends_with(".rutube.ru")
    {
        PilotHostingFamily::DomesticCdn
    } else if host.ends_with(".cdn77.org")
        || host.ends_with(".akamai.net")
        || host.ends_with(".akamaized.net")
        || host.ends_with(".fastly.net")
        || host.ends_with(".cloudfront.net")
        || host.ends_with(".edgekey.net")
        || host.contains("cdn")
    {
        PilotHostingFamily::ForeignCdn
    } else {
        PilotHostingFamily::Direct
    }
}

fn pilot_reachability_set(target: &DomainTarget) -> PilotReachabilitySet {
    if target.is_control || target.host.eq_ignore_ascii_case("control") {
        return PilotReachabilitySet::Control;
    }
    let host = target.host.trim().to_ascii_lowercase();
    if host.ends_with(".ru") || host.ends_with(".su") || host.ends_with(".xn--p1ai") {
        PilotReachabilitySet::Domestic
    } else {
        PilotReachabilitySet::Foreign
    }
}

fn pilot_target_bucket(target: &DomainTarget) -> PilotTargetBucket {
    let hosting_family = pilot_hosting_family(&target.host);
    PilotTargetBucket {
        hosting_family,
        reachability_set: pilot_reachability_set(target),
        ech_likely: matches!(hosting_family, PilotHostingFamily::Cloudflare | PilotHostingFamily::Google),
    }
}

pub(in crate::engine::runners::strategy) fn pilot_bucket_label(target: &DomainTarget) -> String {
    let bucket = pilot_target_bucket(target);
    format!(
        "{:?}:{:?}:ech={}",
        bucket.reachability_set,
        bucket.hosting_family,
        if bucket.ech_likely { "yes" } else { "no" }
    )
    .to_ascii_lowercase()
}

pub(in crate::engine::runners::strategy) fn stratified_pilot_targets(
    domain_targets: &[DomainTarget],
) -> Vec<DomainTarget> {
    let mut selected = Vec::new();
    let mut seen_buckets = std::collections::HashSet::new();
    let mut selected_hosts = std::collections::HashSet::new();

    while selected.len() < 3 {
        let mut seen_reachability = std::collections::HashSet::new();
        let mut seen_hosting = std::collections::HashSet::new();
        for target in &selected {
            let bucket = pilot_target_bucket(target);
            seen_reachability.insert(bucket.reachability_set);
            seen_hosting.insert(bucket.hosting_family);
        }

        let Some(next_target) = domain_targets
            .iter()
            .filter(|target| !selected_hosts.contains(target.host.as_str()))
            .max_by_key(|target| {
                let bucket = pilot_target_bucket(target);
                (
                    matches!(bucket.reachability_set, PilotReachabilitySet::Control),
                    !seen_reachability.contains(&bucket.reachability_set),
                    !seen_hosting.contains(&bucket.hosting_family),
                    bucket.ech_likely,
                    !matches!(bucket.hosting_family, PilotHostingFamily::Direct),
                    matches!(bucket.reachability_set, PilotReachabilitySet::Domestic),
                    std::cmp::Reverse(target.host.as_str()),
                )
            })
        else {
            break;
        };

        selected_hosts.insert(next_target.host.clone());
        let bucket = pilot_target_bucket(next_target);
        if seen_buckets.insert(bucket) {
            selected.push(next_target.clone());
        }
    }

    if selected.is_empty() {
        if let Some(first) = domain_targets.first() {
            selected.push(first.clone());
        }
    }
    selected
}
