use std::collections::BTreeSet;
use std::net::IpAddr;

use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::{Name, RData, RecordType};

use crate::types::EncryptedDnsError;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum IpAnswerFamily {
    Ipv4,
    Ipv6,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct IpAnswerRecord {
    pub ip: IpAddr,
    pub family: IpAnswerFamily,
}

pub(crate) fn build_dns_query(name: &str, record_type: RecordType) -> Result<Vec<u8>, EncryptedDnsError> {
    let id = random_dns_id()?;
    let mut message = Message::new(id, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::new(
        Name::from_ascii(name).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?,
        record_type,
    ));
    message.to_vec().map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))
}

/// Validate that a DNS reply answers the query that was actually sent.
pub fn validate_dns_response_for_query(query: &[u8], response: &[u8]) -> Result<(), EncryptedDnsError> {
    let request = Message::from_vec(query).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let reply = Message::from_vec(response).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    if request.queries.len() != 1
        || request.metadata.message_type != MessageType::Query
        || request.metadata.op_code != OpCode::Query
        || reply.metadata.id != request.metadata.id
        || reply.metadata.message_type != MessageType::Response
        || reply.metadata.op_code != OpCode::Query
        || reply.metadata.response_code != ResponseCode::NoError
        || reply.metadata.truncation
        || reply.queries != request.queries
    {
        return Err(EncryptedDnsError::DnsParse("DNS response does not match query".to_string()));
    }

    let mut valid_names = vec![request.queries[0].name.clone()];
    loop {
        let previous_len = valid_names.len();
        for record in &reply.answers {
            if let RData::CNAME(target) = &record.data
                && record.dns_class == request.queries[0].query_class
                && valid_names.contains(&record.name)
                && !valid_names.contains(&target.0)
            {
                valid_names.push(target.0.clone());
            }
        }
        if valid_names.len() == previous_len {
            break;
        }
    }
    if reply.answers.iter().any(|record| {
        matches!(record.data, RData::A(_) | RData::AAAA(_))
            && (record.dns_class != request.queries[0].query_class || !valid_names.contains(&record.name))
    }) {
        return Err(EncryptedDnsError::DnsParse("DNS answer owner does not match query".to_string()));
    }
    Ok(())
}

/// Return IP answers of the requested type only after validating the response.
pub fn extract_ip_answers_for_query(query: &[u8], response: &[u8]) -> Result<Vec<IpAddr>, EncryptedDnsError> {
    validate_dns_response_for_query(query, response)?;
    let request = Message::from_vec(query).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    Ok(extract_ip_answer_records(response)?
        .into_iter()
        .filter(|record| {
            matches!(
                (&record.family, request.queries[0].query_type),
                (IpAnswerFamily::Ipv4, RecordType::A) | (IpAnswerFamily::Ipv6, RecordType::AAAA)
            )
        })
        .map(|record| record.ip)
        .collect())
}

pub fn extract_ip_answers(packet: &[u8]) -> Result<Vec<String>, EncryptedDnsError> {
    Ok(extract_ip_answer_records(packet)?.into_iter().map(|record| record.ip.to_string()).collect())
}

pub fn extract_ip_answer_records(packet: &[u8]) -> Result<Vec<IpAnswerRecord>, EncryptedDnsError> {
    let message = Message::from_vec(packet).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let mut answers = BTreeSet::new();
    for record in &message.answers {
        match &record.data {
            RData::A(address) => {
                answers.insert(IpAnswerRecord { ip: IpAddr::V4(address.0), family: IpAnswerFamily::Ipv4 });
            }
            RData::AAAA(address) => {
                answers.insert(IpAnswerRecord { ip: IpAddr::V6(address.0), family: IpAnswerFamily::Ipv6 });
            }
            _ => {}
        }
    }
    Ok(answers.into_iter().collect())
}

fn random_dns_id() -> Result<u16, EncryptedDnsError> {
    let rng = ring::rand::SystemRandom::new();
    let mut buf = [0u8; 2];
    ring::rand::SecureRandom::fill(&rng, &mut buf)
        .map_err(|_| EncryptedDnsError::DnsParse("RNG failure".to_string()))?;
    Ok(u16::from_ne_bytes(buf))
}
#[cfg(test)]
mod validated_tests {
    use super::*;
    use hickory_proto::rr::Record;
    use hickory_proto::rr::rdata::{A, CNAME};
    use std::net::Ipv4Addr;

    fn exchange() -> (Vec<u8>, Message) {
        let query = build_dns_query("fixture.test", RecordType::A).unwrap();
        let request = Message::from_vec(&query).unwrap();
        let mut response = Message::response(request.metadata.id, OpCode::Query);
        response.add_query(request.queries[0].clone());
        (query, response)
    }

    #[test]
    fn validated_answers_accept_direct_and_cname_chain_only() {
        let (query, mut response) = exchange();
        let owner = response.queries[0].name.clone();
        let alias = Name::from_ascii("alias.fixture.test").unwrap();
        let target = Name::from_ascii("target.fixture.test").unwrap();
        response.add_answer(Record::from_rdata(owner, 60, RData::CNAME(CNAME(alias.clone()))));
        response.add_answer(Record::from_rdata(target.clone(), 60, RData::A(A(Ipv4Addr::new(198, 18, 0, 1)))));
        response.add_answer(Record::from_rdata(alias, 60, RData::CNAME(CNAME(target))));
        assert_eq!(
            extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).unwrap(),
            vec![IpAddr::V4(Ipv4Addr::new(198, 18, 0, 1))]
        );

        let (query, mut response) = exchange();
        response.add_answer(Record::from_rdata(
            response.queries[0].name.clone(),
            60,
            RData::A(A(Ipv4Addr::new(198, 18, 0, 3))),
        ));
        assert_eq!(
            extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).unwrap(),
            vec![IpAddr::V4(Ipv4Addr::new(198, 18, 0, 3))]
        );
    }

    #[test]
    fn validated_answers_reject_mismatched_header_and_question() {
        let (query, mut response) = exchange();
        response.metadata.id = response.metadata.id.wrapping_add(1);
        assert!(extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).is_err());

        let (query, mut response) = exchange();
        response.queries[0].name = Name::from_ascii("other.test").unwrap();
        assert!(extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).is_err());

        let (query, mut response) = exchange();
        response.metadata.response_code = ResponseCode::ServFail;
        assert!(extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).is_err());

        let (query, _) = exchange();
        assert!(extract_ip_answers_for_query(&query, &query).is_err());
    }

    #[test]
    fn validated_answers_reject_unrelated_owner() {
        let (query, mut response) = exchange();
        response.add_answer(Record::from_rdata(
            Name::from_ascii("other.test").unwrap(),
            60,
            RData::A(A(Ipv4Addr::new(198, 18, 0, 2))),
        ));
        assert!(extract_ip_answers_for_query(&query, &response.to_vec().unwrap()).is_err());
    }

    #[test]
    fn validated_answers_reject_malformed_response() {
        let (query, _) = exchange();
        assert!(extract_ip_answers_for_query(&query, &[0, 1, 2]).is_err());
    }
}
