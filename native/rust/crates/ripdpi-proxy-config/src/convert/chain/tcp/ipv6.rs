use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

use crate::convert::chain::ipv6::{parse_ipv6_extension_profile, ParsedIpv6ExtensionProfile};

pub(crate) fn parse_tcp_ipv6_extension_profile(
    step: &ProxyUiTcpChainStep,
) -> Result<ParsedIpv6ExtensionProfile, ProxyConfigError> {
    parse_ipv6_extension_profile(&step.ipv6_extension_profile)
}
