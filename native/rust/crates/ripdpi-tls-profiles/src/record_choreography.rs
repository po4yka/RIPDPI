use ripdpi_packets::parse_tls_client_hello_layout;

use crate::selected_profile_metadata;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordChoreography {
    SingleRecord,
    HostTailTwoRecord,
    SniTailTwoRecord,
    SniEchTailAdaptive,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlsTemplateFirstFlightPlan {
    pub record_choreography: RecordChoreography,
    pub record_payload_boundaries: Vec<usize>,
    pub ech_bootstrap_policy: &'static str,
    pub ech_bootstrap_resolver_id: Option<&'static str>,
    pub ech_outer_extension_policy: &'static str,
    pub grease_style: &'static str,
    pub ech_capable_template: bool,
    pub ech_present_in_input: bool,
}

impl RecordChoreography {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::SingleRecord => "single_record",
            Self::HostTailTwoRecord => "host_tail_two_record",
            Self::SniTailTwoRecord => "sni_tail_two_record",
            Self::SniEchTailAdaptive => "sni_ech_tail_adaptive",
        }
    }

    fn from_metadata(value: &str) -> Self {
        match value {
            "host_tail_two_record" => Self::HostTailTwoRecord,
            "sni_tail_two_record" => Self::SniTailTwoRecord,
            "sni_ech_tail_adaptive" => Self::SniEchTailAdaptive,
            _ => Self::SingleRecord,
        }
    }
}

pub fn selected_record_choreography(profile: &str) -> RecordChoreography {
    let metadata = selected_profile_metadata(profile);
    RecordChoreography::from_metadata(metadata.template.record_choreography)
}

fn payload_offset(record_offset: usize) -> Option<usize> {
    record_offset.checked_sub(5)
}

fn canonical_boundaries(total: usize, raw: impl IntoIterator<Item = usize>) -> Vec<usize> {
    let mut boundaries = raw.into_iter().filter(|boundary| *boundary > 0 && *boundary < total).collect::<Vec<_>>();
    boundaries.sort_unstable();
    boundaries.dedup();
    boundaries.push(total);
    boundaries
}

fn serialize_records(header: [u8; 3], payload: &[u8], boundaries: &[usize]) -> Option<Vec<u8>> {
    let mut output = Vec::with_capacity(payload.len() + (boundaries.len() * 5));
    let mut cursor = 0usize;
    for &boundary in boundaries {
        let chunk = payload.get(cursor..boundary)?;
        let len = u16::try_from(chunk.len()).ok()?;
        output.extend_from_slice(&header);
        output.extend_from_slice(&len.to_be_bytes());
        output.extend_from_slice(chunk);
        cursor = boundary;
    }
    (cursor == payload.len()).then_some(output)
}

pub fn plan_first_flight(profile: &str, client_hello: &[u8]) -> Option<TlsTemplateFirstFlightPlan> {
    let metadata = selected_profile_metadata(profile);
    let layout = parse_tls_client_hello_layout(client_hello)?;
    let total = layout.record_payload_len;
    if 5usize.checked_add(total)? != client_hello.len() {
        return None;
    }

    let markers = layout.markers;
    let record_choreography = RecordChoreography::from_metadata(metadata.template.record_choreography);
    let boundaries = match record_choreography {
        RecordChoreography::SingleRecord => Vec::new(),
        RecordChoreography::HostTailTwoRecord => vec![payload_offset(markers.host_end)?],
        RecordChoreography::SniTailTwoRecord => vec![payload_offset(markers.sni_ext_start)?],
        RecordChoreography::SniEchTailAdaptive => {
            let mut boundaries = vec![payload_offset(markers.sni_ext_start)?];
            if let Some(ech_start) = markers.ech_ext_start.and_then(payload_offset) {
                boundaries.push(ech_start);
            }
            boundaries
        }
    };

    Some(TlsTemplateFirstFlightPlan {
        record_choreography,
        record_payload_boundaries: canonical_boundaries(total, boundaries),
        ech_bootstrap_policy: metadata.template.ech_bootstrap_policy,
        ech_bootstrap_resolver_id: metadata.template.ech_bootstrap_resolver_id,
        ech_outer_extension_policy: metadata.template.ech_outer_extension_policy,
        grease_style: metadata.template.grease_style,
        ech_capable_template: metadata.template.ech_capable,
        ech_present_in_input: markers.ech_ext_start.is_some(),
    })
}

pub fn planned_record_payload_boundaries(profile: &str, client_hello: &[u8]) -> Option<Vec<usize>> {
    Some(plan_first_flight(profile, client_hello)?.record_payload_boundaries)
}

pub fn planned_record_payload_lengths(profile: &str, client_hello: &[u8]) -> Option<Vec<usize>> {
    let boundaries = planned_record_payload_boundaries(profile, client_hello)?;
    let mut cursor = 0usize;
    let mut lengths = Vec::with_capacity(boundaries.len());
    for boundary in boundaries {
        lengths.push(boundary.saturating_sub(cursor));
        cursor = boundary;
    }
    Some(lengths)
}

pub fn apply_record_choreography(profile: &str, client_hello: &[u8]) -> Option<Vec<u8>> {
    let boundaries = plan_first_flight(profile, client_hello)?.record_payload_boundaries;
    let layout = parse_tls_client_hello_layout(client_hello)?;
    let payload = client_hello.get(5..5 + layout.record_payload_len)?;
    let header = [*client_hello.first()?, *client_hello.get(1)?, *client_hello.get(2)?];
    serialize_records(header, payload, &boundaries)
}
