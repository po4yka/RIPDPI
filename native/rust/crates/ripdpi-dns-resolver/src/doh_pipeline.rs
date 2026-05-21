mod builder;
mod cache;
mod https_bindings;
mod ip_candidates;
mod lookup;
mod types;

use std::sync::Arc;

use crate::resolver::EncryptedDnsResolver;

use cache::LookupCache;

pub use builder::DohResolverPipelineBuilder;
pub use ip_candidates::doh_ip_answer_candidates;
pub use types::{
    DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohIpAnswerCandidate, DohIpFamily, DohResolverRole,
};

struct DohResolverPipelineInner {
    primary: EncryptedDnsResolver,
    secondary: EncryptedDnsResolver,
    cache: LookupCache,
}

#[derive(Clone)]
pub struct DohResolverPipeline {
    inner: Arc<DohResolverPipelineInner>,
}

impl std::fmt::Debug for DohResolverPipeline {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("DohResolverPipeline").finish_non_exhaustive()
    }
}
