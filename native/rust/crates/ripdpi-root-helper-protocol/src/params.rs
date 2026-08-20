use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FakeRstParams {
    pub default_ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FakeTcpParams {
    pub original_prefix: Vec<u8>,
    pub fake_prefix: Vec<u8>,
    pub ttl: u8,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub secondary_fake_prefix: Option<Vec<u8>>,
    #[serde(default)]
    pub timestamp_delta_ticks: Option<i32>,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub tcp_flags_orig_set: u16,
    #[serde(default)]
    pub tcp_flags_orig_unset: u16,
    #[serde(default)]
    pub require_raw_path: bool,
    #[serde(default)]
    pub force_raw_original: bool,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
    #[serde(default)]
    pub wait_enabled: bool,
    #[serde(default)]
    pub wait_poll_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlaggedTcpPayloadParams {
    pub payload: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeqOvlParams {
    pub real_chunk: Vec<u8>,
    pub fake_prefix: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MultiDisorderParams {
    pub payload: Vec<u8>,
    pub segments: Vec<SegmentSpec>,
    pub default_ttl: u8,
    #[serde(default)]
    pub inter_segment_delay_ms: u32,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SegmentSpec {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderedTcpSegmentParams {
    pub payload: Vec<u8>,
    pub ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    pub sequence_offset: usize,
    #[serde(default)]
    pub use_fake_timestamp: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderedTcpSegmentsParams {
    pub segments: Vec<OrderedTcpSegmentParams>,
    pub original_payload_len: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub timestamp_delta_ticks: Option<i32>,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
    #[serde(default)]
    pub wait_enabled: bool,
    #[serde(default)]
    pub wait_poll_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpFragTcpParams {
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpFragUdpParams {
    pub target_addr: String,
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
    pub ipv6_hop_by_hop: bool,
    pub ipv6_dest_opt: bool,
    pub ipv6_dest_opt_fragmentable: bool,
    pub ipv6_routing: bool,
    pub ipv6_second_frag_next_override: Option<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RawIpPacketParams {
    pub target_addr: String,
    pub packet: Vec<u8>,
}

#[cfg(test)]
mod tests {
    use super::{
        FakeRstParams, FakeTcpParams, FlaggedTcpPayloadParams, IpFragTcpParams, IpFragUdpParams, MultiDisorderParams,
        OrderedTcpSegmentParams, OrderedTcpSegmentsParams, RawIpPacketParams, SegmentSpec, SeqOvlParams,
    };

    /// Round-trip a canonical compact JSON form through `from_str` then
    /// `to_string` and assert it is byte-identical. For one params struct this
    /// pins every field name, the field order, and the value encoding —
    /// notably `Vec<u8>` as a JSON number array and `Option::None` as `null`.
    /// This is the frozen wire representation; see
    /// `docs/architecture/ROOT_HELPER_CONTRACT.md`.
    fn assert_params_json_stable<T>(canonical: &str)
    where
        T: serde::de::DeserializeOwned + serde::Serialize,
    {
        let parsed: T = serde_json::from_str(canonical).expect("deserialize canonical params JSON");
        let reencoded = serde_json::to_string(&parsed).expect("serialize params");
        assert_eq!(reencoded, canonical, "params JSON representation drifted");
    }

    #[test]
    fn params_json_representations_are_stable() {
        assert_params_json_stable::<FakeRstParams>(
            r#"{"default_ttl":64,"tcp_flags_set":4,"tcp_flags_unset":2,"ipv4_identification":513}"#,
        );
        assert_params_json_stable::<FakeTcpParams>(concat!(
            r#"{"original_prefix":[1,2],"fake_prefix":[3],"ttl":4,"default_ttl":64,"md5sig":true,"#,
            r#""secondary_fake_prefix":[5,6],"timestamp_delta_ticks":-7,"tcp_flags_set":8,"tcp_flags_unset":9,"#,
            r#""tcp_flags_orig_set":10,"tcp_flags_orig_unset":11,"require_raw_path":true,"force_raw_original":false,"#,
            r#""ipv4_identifications":[12,13],"wait_enabled":true,"wait_poll_ms":14}"#,
        ));
        assert_params_json_stable::<FlaggedTcpPayloadParams>(concat!(
            r#"{"payload":[1,2,3],"default_ttl":64,"md5sig":false,"tcp_flags_set":0,"#,
            r#""tcp_flags_unset":0,"ipv4_identification":null}"#,
        ));
        assert_params_json_stable::<SeqOvlParams>(concat!(
            r#"{"real_chunk":[1],"fake_prefix":[2,3],"default_ttl":64,"md5sig":true,"#,
            r#""tcp_flags_set":1,"tcp_flags_unset":0,"ipv4_identification":42}"#,
        ));
        assert_params_json_stable::<MultiDisorderParams>(concat!(
            r#"{"payload":[1,2,3,4],"segments":[{"start":0,"end":2},{"start":2,"end":4}],"default_ttl":64,"#,
            r#""inter_segment_delay_ms":5,"md5sig":false,"tcp_flags_set":0,"tcp_flags_unset":0,"#,
            r#""ipv4_identifications":[7,8]}"#,
        ));
        assert_params_json_stable::<SegmentSpec>(r#"{"start":0,"end":2}"#);
        assert_params_json_stable::<OrderedTcpSegmentParams>(concat!(
            r#"{"payload":[9],"ttl":64,"tcp_flags_set":0,"tcp_flags_unset":0,"#,
            r#""sequence_offset":3,"use_fake_timestamp":true}"#,
        ));
        assert_params_json_stable::<OrderedTcpSegmentsParams>(concat!(
            r#"{"segments":[{"payload":[9],"ttl":64,"tcp_flags_set":0,"tcp_flags_unset":0,"#,
            r#""sequence_offset":3,"use_fake_timestamp":true}],"original_payload_len":1,"default_ttl":64,"#,
            r#""md5sig":false,"timestamp_delta_ticks":null,"ipv4_identifications":[],"wait_enabled":false,"#,
            r#""wait_poll_ms":0}"#,
        ));
        assert_params_json_stable::<IpFragTcpParams>(concat!(
            r#"{"payload":[1,2,3],"split_offset":2,"default_ttl":64,"disorder":true,"#,
            r#""tcp_flags_set":0,"tcp_flags_unset":0,"ipv4_identification":null}"#,
        ));
        assert_params_json_stable::<IpFragUdpParams>(concat!(
            r#"{"target_addr":"203.0.113.5:443","payload":[1,2],"split_offset":1,"#,
            r#""default_ttl":64,"disorder":false,"ipv4_identification":7,"#,
            r#""ipv6_hop_by_hop":false,"ipv6_dest_opt":false,"ipv6_dest_opt_fragmentable":false,"#,
            r#""ipv6_routing":false,"ipv6_second_frag_next_override":null}"#,
        ));
        assert_params_json_stable::<RawIpPacketParams>(r#"{"target_addr":"203.0.113.5:443","packet":[1,2,255]}"#);
    }

    #[test]
    fn required_params_are_rejected_when_missing() {
        // Each input omits exactly one required (non-`#[serde(default)]`) field.
        assert!(serde_json::from_str::<FakeRstParams>(r#"{"tcp_flags_set":4}"#).is_err());
        assert!(
            serde_json::from_str::<FakeTcpParams>(r#"{"original_prefix":[],"fake_prefix":[],"default_ttl":64}"#)
                .is_err()
        );
        assert!(serde_json::from_str::<FlaggedTcpPayloadParams>(r#"{"default_ttl":64}"#).is_err());
        assert!(serde_json::from_str::<SeqOvlParams>(r#"{"fake_prefix":[2],"default_ttl":64}"#).is_err());
        assert!(serde_json::from_str::<MultiDisorderParams>(r#"{"payload":[1],"default_ttl":64}"#).is_err());
        assert!(serde_json::from_str::<SegmentSpec>(r#"{"start":0}"#).is_err());
        assert!(serde_json::from_str::<OrderedTcpSegmentParams>(r#"{"payload":[1],"ttl":64}"#).is_err());
        assert!(serde_json::from_str::<OrderedTcpSegmentsParams>(r#"{"segments":[],"default_ttl":64}"#).is_err());
        assert!(serde_json::from_str::<IpFragTcpParams>(r#"{"payload":[1],"default_ttl":64}"#).is_err());
        assert!(
            serde_json::from_str::<IpFragUdpParams>(r#"{"payload":[1],"split_offset":0,"default_ttl":64}"#).is_err()
        );
        assert!(serde_json::from_str::<RawIpPacketParams>(r#"{"target_addr":"203.0.113.5:443"}"#).is_err());
    }

    #[test]
    fn optional_params_fall_back_to_defaults_when_absent() {
        let fake_rst: FakeRstParams = serde_json::from_str(r#"{"default_ttl":64}"#).expect("minimal FakeRstParams");
        assert_eq!(fake_rst.tcp_flags_set, 0);
        assert_eq!(fake_rst.tcp_flags_unset, 0);
        assert!(fake_rst.ipv4_identification.is_none());

        let fake_tcp: FakeTcpParams =
            serde_json::from_str(r#"{"original_prefix":[],"fake_prefix":[],"ttl":1,"default_ttl":64}"#)
                .expect("minimal FakeTcpParams");
        assert!(!fake_tcp.md5sig);
        assert!(!fake_tcp.wait_enabled);
        assert_eq!(fake_tcp.wait_poll_ms, 0);
        assert!(fake_tcp.ipv4_identifications.is_empty());
        assert!(fake_tcp.secondary_fake_prefix.is_none());

        let ip_frag: IpFragTcpParams = serde_json::from_str(r#"{"payload":[1],"split_offset":0,"default_ttl":64}"#)
            .expect("minimal IpFragTcpParams");
        assert!(!ip_frag.disorder);
        assert!(ip_frag.ipv4_identification.is_none());
    }
}
