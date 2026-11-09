//! Which HTTPS record and which ECHConfig reach the TLS stack. Wire bytes are
//! built by hand; expected values come from RFC 9460 9, RFC 9848 4 and RFC 9849 4.

use ripdpi_tls_profiles::{EchFacadeError, EchOutboundError, OutboundEchConfig};

use super::{EchResolutionOutcome, EchStep, https_response_ech_step, outbound_ech_config};

const HTTPS: u16 = 65;
const NAME: &str = "relay.example";

fn name(n: &str) -> Vec<u8> {
    let mut out = Vec::new();
    for label in n.trim_end_matches('.').split('.').filter(|l| !l.is_empty()) {
        out.push(u8::try_from(label.len()).expect("label fits"));
        out.extend(label.as_bytes());
    }
    out.push(0);
    out
}

fn param(key: u16, value: &[u8]) -> Vec<u8> {
    let mut out = key.to_be_bytes().to_vec();
    out.extend(u16::try_from(value.len()).expect("value fits").to_be_bytes());
    out.extend(value);
    out
}

/// An HTTPS answer for `NAME` with (priority, target, port, ECHConfigList) records.
fn answer(records: &[(u16, &str, Option<u16>, Option<&[u8]>)]) -> Vec<u8> {
    let mut p = vec![0x12, 0x34, 0x81, 0x80, 0, 1];
    p.extend(u16::try_from(records.len()).expect("count fits").to_be_bytes());
    p.extend([0, 0, 0, 0]);
    p.extend(name(NAME));
    p.extend(HTTPS.to_be_bytes());
    p.extend(1u16.to_be_bytes());
    for (priority, target, port, ech) in records {
        let mut rdata = priority.to_be_bytes().to_vec();
        rdata.extend(name(target));
        if let Some(port) = port {
            rdata.extend(param(3, &port.to_be_bytes()));
        }
        if let Some(ech) = ech {
            rdata.extend(param(5, ech));
        }
        p.extend([0xc0, 0x0c]);
        p.extend(HTTPS.to_be_bytes());
        p.extend(1u16.to_be_bytes());
        p.extend(300u32.to_be_bytes());
        p.extend(u16::try_from(rdata.len()).expect("rdata fits").to_be_bytes());
        p.extend(rdata);
    }
    p
}

/// A v18 ECHConfig entry with an X25519 key of 32 bytes.
fn entry(config_id: u8, public_name: &[u8]) -> Vec<u8> {
    let mut c = vec![config_id];
    c.extend(0x0020u16.to_be_bytes());
    c.extend(32u16.to_be_bytes());
    c.extend([0x42; 32]);
    c.extend(4u16.to_be_bytes());
    c.extend(0x0001u16.to_be_bytes());
    c.extend(0x0001u16.to_be_bytes());
    c.push(0);
    c.push(u8::try_from(public_name.len()).expect("name fits"));
    c.extend(public_name);
    c.extend(0u16.to_be_bytes());
    let mut e = 0xfe0du16.to_be_bytes().to_vec();
    e.extend(u16::try_from(c.len()).expect("config fits").to_be_bytes());
    e.extend(c);
    e
}

fn list(entries: &[Vec<u8>]) -> Vec<u8> {
    let body: Vec<u8> = entries.concat();
    let mut l = u16::try_from(body.len()).expect("list fits").to_be_bytes().to_vec();
    l.extend(body);
    l
}

struct FixedLookup(Vec<u8>);

impl ripdpi_tls_profiles::OutboundEchResolver for FixedLookup {
    fn resolve_https_ech_config_list(
        &self,
        _request: &ripdpi_tls_profiles::EchLookupRequest<'_>,
    ) -> Result<ripdpi_tls_profiles::EchLookupOutcome, EchFacadeError> {
        super::ech_lookup_outcome(match https_response_ech_step(&self.0) {
            EchStep::Done(outcome) => outcome,
            EchStep::Alias(_) => EchResolutionOutcome::NotPublished,
        })
    }
}

fn setup(records: &[(u16, &str, Option<u16>, Option<&[u8]>)]) -> Result<Option<OutboundEchConfig>, EchFacadeError> {
    outbound_ech_config(NAME, &FixedLookup(answer(records)))
}

#[test]
fn bad_public_name_entry_is_filtered_out_of_the_real_list() {
    let good = entry(1, b"ech.example");
    let published = list(&[good.clone(), entry(2, b"bad..example")]);

    let config = setup(&[(1, ".", None, Some(&published))]).expect("lookup").expect("real ECH");

    assert_eq!(config.public_name, "ech.example");
    assert_eq!(config.config_list, list(&[good]));
}

#[test]
fn list_without_a_supported_entry_is_rejected_not_a_lookup_failure() {
    let mut unknown = 0xfe0eu16.to_be_bytes().to_vec();
    unknown.extend(3u16.to_be_bytes());
    unknown.extend([1, 2, 3]);
    let published = list(&[unknown]);

    let result = setup(&[(1, ".", None, Some(&published))]);

    assert!(matches!(result, Err(EchFacadeError::Backend(EchOutboundError::ConfigRejected(_)))), "{result:?}");
}

#[test]
fn other_target_name_with_port_is_not_used() {
    let published = list(&[entry(1, b"ech.example")]);

    assert_eq!(setup(&[(1, "other.example", Some(8443), Some(&published))]).expect("lookup"), None);
}

#[test]
fn own_target_name_with_port_is_not_used() {
    let published = list(&[entry(1, b"ech.example")]);

    assert_eq!(setup(&[(1, ".", Some(8443), Some(&published))]).expect("lookup"), None);
}

#[test]
fn dot_target_without_port_is_real() {
    let published = list(&[entry(1, b"ech.example")]);

    let config = setup(&[(1, ".", None, Some(&published))]).expect("lookup").expect("real ECH");

    assert_eq!(config.config_list, published);
}

#[test]
fn compatible_lower_priority_record_is_used() {
    let other = list(&[entry(1, b"other.example")]);
    let own = list(&[entry(2, b"ech.example")]);

    let config = setup(&[(1, "svc.example", None, Some(&other)), (2, NAME, None, Some(&own))])
        .expect("lookup")
        .expect("real ECH");

    assert_eq!(config.public_name, "ech.example");
    assert_eq!(config.config_list, own);
}
