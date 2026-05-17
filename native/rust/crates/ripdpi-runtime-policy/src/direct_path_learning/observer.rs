use super::key::TupleKey;

pub use ripdpi_runtime_decision_ports::policy_ports::DirectPathLearningObserver;

pub(super) fn emit_learning_signal(
    observer: Option<&dyn DirectPathLearningObserver>,
    tuple_key: &TupleKey,
    event: &'static str,
    strategy_family: Option<&str>,
) {
    if let Some(observer) = observer {
        observer.on_direct_path_learning_signal(
            tuple_key.authority.as_str(),
            tuple_key.ip_set_digest.as_str(),
            event,
            strategy_family,
        );
    }
}
