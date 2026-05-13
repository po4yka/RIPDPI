macro_rules! impl_connectivity_runner {
    ($runner:ty, $family:ty, $stage:ident) => {
        impl crate::engine::runtime::ExecutionStageRunner for $runner {
            fn id(&self) -> crate::engine::runtime::ExecutionStageId {
                crate::engine::runtime::ExecutionStageId::$stage
            }

            fn phase(&self) -> &'static str {
                <$family as super::support::ConnectivityProbeFamily>::PHASE
            }

            fn total_steps(&self, plan: &crate::engine::runtime::ExecutionPlan) -> usize {
                super::support::target_count::<$family>(plan)
            }

            fn run_collecting(
                &self,
                plan: &crate::engine::runtime::ExecutionPlan,
                cancel: &std::sync::atomic::AtomicBool,
                tls_verifier: Option<&std::sync::Arc<dyn rustls::client::danger::ServerCertVerifier>>,
            ) -> Option<Vec<crate::engine::runtime::CollectedStep>> {
                super::support::collect_family_steps::<$family>(plan, cancel, tls_verifier)
            }
        }
    };
}

mod circumvention;
mod dns;
mod environment;
mod quic;
mod service;
mod support;
mod tcp;
mod telegram;
mod telegram_record;
mod throughput;
mod web;

pub(super) use circumvention::CircumventionRunner;
pub(super) use dns::DnsRunner;
pub(super) use environment::EnvironmentRunner;
pub(super) use quic::QuicRunner;
pub(super) use service::ServiceRunner;
pub(super) use tcp::TcpRunner;
pub(super) use telegram::TelegramRunner;
pub(super) use throughput::ThroughputRunner;
pub(super) use web::WebRunner;
