use crate::https_service_binding::{HttpsRr, HttpsSvcbParseError, parse_https_service_bindings};

use super::{DohBatchLookup, DohBatchRecordType, DohResolverPipeline};

impl DohResolverPipeline {
    pub fn https_service_bindings(&self, lookup: &DohBatchLookup) -> Result<Vec<HttpsRr>, HttpsSvcbParseError> {
        lookup.https_service_bindings()
    }
}

impl DohBatchLookup {
    pub fn https_service_bindings(&self) -> Result<Vec<HttpsRr>, HttpsSvcbParseError> {
        let mut bindings = Vec::new();
        for record in &self.records {
            if matches!(record.record_type, DohBatchRecordType::Https | DohBatchRecordType::Svcb) {
                bindings.extend(parse_https_service_bindings(&record.response_bytes)?);
            }
        }
        Ok(bindings)
    }

    pub fn ech_capable(&self) -> Result<bool, HttpsSvcbParseError> {
        Ok(self.https_service_bindings()?.iter().any(|record| record.ech_capable))
    }
}
