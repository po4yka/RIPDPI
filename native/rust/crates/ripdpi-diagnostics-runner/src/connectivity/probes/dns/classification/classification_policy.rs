use super::answer_classification::DnsAnswerClass;
use super::https_ech_classification::DnsHttpsClass;

pub(super) fn resolve_dns_classification(
    answer_class: Option<DnsAnswerClass>,
    https_class: DnsHttpsClass,
) -> Option<&'static str> {
    // Answer integrity outranks HTTPS-RR capability: an ech parameter does not
    // make a poisoned or divergent A/AAAA answer trustworthy (RFC 9460 s9).
    match (https_class, answer_class) {
        (_, Some(DnsAnswerClass::Poisoned)) => Some("POISONED"),
        (_, Some(DnsAnswerClass::Divergent)) => Some("DIVERGENT"),
        (DnsHttpsClass::EchCapable, _) => Some("ECH_CAPABLE"),
        (DnsHttpsClass::NoHttpsRr, Some(DnsAnswerClass::Clean)) => Some("NO_HTTPS_RR"),
        (_, Some(answer_class)) => Some(answer_class.as_str()),
        (DnsHttpsClass::NoHttpsRr, None) => Some("NO_HTTPS_RR"),
        _ => None,
    }
}
