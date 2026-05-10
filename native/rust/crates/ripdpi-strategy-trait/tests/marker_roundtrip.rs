use std::collections::HashMap;

use ripdpi_strategy_trait::{Dissect, L7Protocol, MarkerName};

#[test]
fn markers_roundtrip_through_dissect_map() {
    let markers = HashMap::from([(MarkerName::Host, 5), (MarkerName::HostEnd, 16), (MarkerName::SniExt, 42)]);
    let dissect = Dissect { proto: L7Protocol::Unknown, src_port: 12345, dst_port: 443, is_ipv6: false, markers };

    assert_eq!(dissect.markers.get(&MarkerName::Host), Some(&5));
    assert_eq!(dissect.markers.get(&MarkerName::HostEnd), Some(&16));
    assert_eq!(dissect.markers.get(&MarkerName::SniExt), Some(&42));
}
