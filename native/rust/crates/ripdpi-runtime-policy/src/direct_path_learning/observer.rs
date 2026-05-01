use super::key::TupleKey;

pub trait DirectPathLearningObserver {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    );
}

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
