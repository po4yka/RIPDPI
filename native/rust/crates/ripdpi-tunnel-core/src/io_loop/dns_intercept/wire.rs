use hickory_proto::op::{Message, MessageType, ResponseCode};
use hickory_proto::rr::DNSClass;

pub(crate) struct ParsedDnsQuery {
    pub(crate) host: String,
    message: Message,
}

impl ParsedDnsQuery {
    pub(crate) fn refused_response(&self) -> Result<Vec<u8>, hickory_proto::ProtoError> {
        let mut response =
            Message::error_msg(self.message.metadata.id, self.message.metadata.op_code, ResponseCode::Refused);
        response.metadata.recursion_desired = self.message.metadata.recursion_desired;
        response.add_query(self.message.queries[0].clone());
        response.to_vec()
    }
}

/// Parses the one supported DNS request shape. All split-DNS decisions and
/// DNS-cache question extraction share this parser.
pub(crate) fn parse_dns_query(packet: &[u8]) -> Option<ParsedDnsQuery> {
    let message = Message::from_vec(packet).ok()?;
    if message.metadata.message_type != MessageType::Query || message.queries.len() != 1 {
        return None;
    }
    let query = &message.queries[0];
    if query.query_class() != DNSClass::IN {
        return None;
    }
    let host = query.name().to_utf8().trim_end_matches('.').to_string();
    if host.is_empty() {
        return None;
    }
    Some(ParsedDnsQuery { host, message })
}

pub(in crate::io_loop) fn dns_query_name(packet: &[u8]) -> Option<String> {
    parse_dns_query(packet).map(|query| query.host)
}
