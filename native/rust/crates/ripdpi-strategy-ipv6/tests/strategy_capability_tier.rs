mod common;

use ripdpi_strategy_ipv6::{Ipv6ExtHdrStrategy, Ipv6ExtType};
use ripdpi_strategy_trait::{
    CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, DesyncStrategy, Dissect, RuntimeCapability,
    StrategyVerdict,
};

#[test]
fn strategy_declares_vpn_tier_and_matches_only_ipv6() {
    let strategy = Ipv6ExtHdrStrategy::new(Ipv6ExtType::DestOpts);
    let descriptor = strategy.describe();

    assert_eq!(descriptor.required_tier, CapabilityTier::Tier3);
    assert_eq!(descriptor.required_capabilities, vec![RuntimeCapability::VpnMode]);

    let ipv6 = Dissect { is_ipv6: true, ..Dissect::default() };
    let ipv4 = Dissect { is_ipv6: false, ..Dissect::default() };
    let caps = common::vpn_caps();
    let packet = common::ipv6_tcp_packet();
    let conn = ConnectionState::default();

    assert!(strategy.matches(&common::vpn_ipv6_context(&ipv6, &conn, &caps, &packet)));
    assert!(!strategy.matches(&common::vpn_ipv6_context(&ipv4, &conn, &caps, &packet)));
}

#[test]
fn strategy_appends_rawsend_for_vpn_ipv6_packet() {
    let strategy = Ipv6ExtHdrStrategy::new(Ipv6ExtType::DestOpts);
    let dissect = Dissect { is_ipv6: true, ..Dissect::default() };
    let caps = common::vpn_caps();
    let packet = common::ipv6_tcp_packet();
    let conn = ConnectionState::default();
    let ctx = common::vpn_ipv6_context(&dissect, &conn, &caps, &packet);
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("plan");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert!(matches!(plan.actions.as_slice(), [DesyncAction::RawSend(output)] if output[6] == 60));
}
