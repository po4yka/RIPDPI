use crate::types::ProxyConfigError;

#[derive(Clone, Copy)]
pub(crate) struct ParsedIpv6ExtensionProfile {
    pub(crate) hop_by_hop: bool,
    pub(crate) dest_opt: bool,
    pub(crate) dest_opt2: bool,
}

pub(crate) fn parse_ipv6_extension_profile(value: &str) -> Result<ParsedIpv6ExtensionProfile, ProxyConfigError> {
    match value.trim() {
        "" | "none" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: false, dest_opt2: false }),
        "hopByHop" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: false }),
        "hopByHop2" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: true }),
        "destOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: true, dest_opt2: false }),
        "hopByHopDestOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: true, dest_opt2: false }),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Unsupported ipv6ExtensionProfile; expected none, hopByHop, hopByHop2, destOpt, or hopByHopDestOpt"
                .to_string(),
        )),
    }
}
