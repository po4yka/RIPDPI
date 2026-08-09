mod summary;

pub(crate) use summary::{ReportBuildContext, build_report};
pub(in crate::engine) use summary::{connectivity_analytics_summary, connectivity_summary};
