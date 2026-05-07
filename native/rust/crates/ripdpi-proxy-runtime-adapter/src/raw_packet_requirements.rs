use std::io;

use ripdpi_config::{RuntimeConfig, TcpChainStepKind, UdpChainStepKind};
use ripdpi_runtime_platform::raw_packet::{probe_ip_fragmentation_capabilities, IpFragmentationCapabilities};

pub fn validate_ip_fragmentation_support(config: &RuntimeConfig) -> io::Result<()> {
    if !requires_raw_packet_desync(config) {
        return Ok(());
    }

    let capabilities = probe_ip_fragmentation_capabilities(config.process.protect_path.as_deref())?;
    validate_ip_fragmentation_capabilities(config, capabilities)
}

fn requires_raw_packet_desync(config: &RuntimeConfig) -> bool {
    requires_packet_owned_tcp(config) || requires_udp_ipfrag(config)
}

fn requires_packet_owned_tcp(config: &RuntimeConfig) -> bool {
    config
        .groups
        .iter()
        .flat_map(ripdpi_config::DesyncGroup::effective_tcp_chain)
        .any(|step| matches!(step.kind(), TcpChainStepKind::IpFrag2 | TcpChainStepKind::MultiDisorder))
}

fn requires_udp_ipfrag(config: &RuntimeConfig) -> bool {
    config
        .groups
        .iter()
        .flat_map(ripdpi_config::DesyncGroup::effective_udp_chain)
        .any(|step| step.kind == UdpChainStepKind::IpFrag2Udp)
}

fn validate_ip_fragmentation_capabilities(
    config: &RuntimeConfig,
    capabilities: IpFragmentationCapabilities,
) -> io::Result<()> {
    let requires_packet_owned_tcp = requires_packet_owned_tcp(config);
    let requires_udp_ipfrag = requires_udp_ipfrag(config);
    if !requires_packet_owned_tcp && !requires_udp_ipfrag {
        return Ok(());
    }

    let mut missing = Vec::new();
    if (requires_packet_owned_tcp || requires_udp_ipfrag) && !capabilities.raw_ipv4 {
        missing.push("raw IPv4 sockets");
    }
    if (requires_packet_owned_tcp || requires_udp_ipfrag) && config.network.ipv6 && !capabilities.raw_ipv6 {
        missing.push("raw IPv6 sockets");
    }
    if requires_packet_owned_tcp && !capabilities.tcp_repair {
        missing.push("TCP repair");
    }
    if missing.is_empty() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::Unsupported, format!("raw-packet desync requires {}", missing.join(", "))))
    }
}

#[cfg(test)]
mod tests {
    use std::io::ErrorKind;

    use ripdpi_config::{
        DesyncGroup, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
    };

    use super::validate_ip_fragmentation_capabilities;
    use crate::platform::raw_packet::IpFragmentationCapabilities;

    fn runtime_config_with_ipfrag(tcp: bool, udp: bool, ipv6: bool) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = ipv6;
        let mut group = DesyncGroup::new(0);
        if tcp {
            group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::host(2)));
        }
        if udp {
            group.actions.udp_chain.push(UdpChainStep {
                kind: UdpChainStepKind::IpFrag2Udp,
                count: 0,
                split_bytes: 8,
                activation_filter: None,
                ip_frag_disorder: false,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt2: false,
                ipv6_frag_next_override: None,
            });
        }
        config.groups = vec![group];
        config
    }

    fn runtime_config_with_multidisorder(ipv6: bool) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = ipv6;
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(0)));
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(2)));
        config.groups = vec![group];
        config
    }

    #[test]
    fn ipfrag_capability_validation_allows_non_fragmenting_configs() {
        let config = RuntimeConfig::default();

        validate_ip_fragmentation_capabilities(&config, IpFragmentationCapabilities::default())
            .expect("non-ipfrag configs should skip capability gating");
    }

    #[test]
    fn ipfrag_capability_validation_requires_ipv6_raw_socket_when_enabled() {
        let config = runtime_config_with_ipfrag(false, true, true);
        let err = validate_ip_fragmentation_capabilities(
            &config,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("ipv6 ipfrag should require raw ipv6");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("raw IPv6 sockets"));
    }

    #[test]
    fn ipfrag_capability_validation_requires_tcp_repair_for_tcp_steps() {
        let config = runtime_config_with_ipfrag(true, false, false);
        let err = validate_ip_fragmentation_capabilities(
            &config,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("tcp ipfrag should require tcp repair");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("TCP repair"));
    }

    #[test]
    fn multidisorder_capability_validation_requires_tcp_repair_for_packet_owned_tcp() {
        let config = runtime_config_with_multidisorder(false);
        let err = validate_ip_fragmentation_capabilities(
            &config,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("multidisorder should require tcp repair");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("TCP repair"));
    }

    #[test]
    fn multidisorder_capability_validation_accepts_raw_sockets_and_tcp_repair() {
        let config = runtime_config_with_multidisorder(true);

        validate_ip_fragmentation_capabilities(
            &config,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true },
        )
        .expect("multidisorder should pass when raw sockets and tcp repair are available");
    }

    #[test]
    fn ipfrag_capability_validation_does_not_require_ipv6_when_disabled() {
        let config = runtime_config_with_ipfrag(false, true, false);

        validate_ip_fragmentation_capabilities(
            &config,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect("ipv4-only ipfrag should not require raw ipv6");
    }

    #[test]
    fn ipfrag_capability_helpers_distinguish_tcp_and_udp_requirements() {
        let udp_only = IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: false };
        assert!(udp_only.supports_udp_ip_fragmentation(true));
        assert!(!udp_only.supports_tcp_ip_fragmentation(true));

        let tcp_and_udp = IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true };
        assert!(tcp_and_udp.supports_udp_ip_fragmentation(true));
        assert!(tcp_and_udp.supports_tcp_ip_fragmentation(true));
    }
}
