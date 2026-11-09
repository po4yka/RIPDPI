use hickory_proto::op::{Message, ResponseCode};

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
        // RFC 6891 6.1.1: answer an OPT request with an OPT; RFC 3225 copies DO.
        if let Some(request_edns) = &self.message.edns {
            let mut edns = hickory_proto::op::Edns::new();
            edns.set_max_payload(1232).set_dnssec_ok(request_edns.flags().dnssec_ok);
            response.edns = Some(edns);
        }
        response.to_vec()
    }

    pub(super) fn new(host: String, message: Message) -> Self {
        Self { host, message }
    }
}
