pub(super) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(super) fn new(runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.id(), runner)).collect();
        Self { runners }
    }

    pub(super) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.stage_order
            .iter()
            .filter_map(|stage| self.runners.get(stage))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }

    pub(super) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        // For CONNECTIVITY scans, DNS + TCP + QUIC are independent I/O-bound
        // stages that can run concurrently. We collect their stages into a
        // parallel group and run them with std::thread::scope, then merge the
        // results back into runtime in order before continuing sequentially.
        const PARALLEL_GROUP: &[ExecutionStageId] =
            &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];

        let is_connectivity = matches!(plan.request.kind, ScanKind::Connectivity);

        // Track which stages in the parallel group we've already handled so we
        // can skip them in the sequential loop below.
        let mut parallel_done = std::collections::HashSet::new();

        if is_connectivity {
            // Collect runners for the parallel group that are present in this
            // plan's stage_order and have at least one step.
            let parallel_runners: Vec<&ExecutionStageId> = plan
                .stage_order
                .iter()
                .filter(|stage| {
                    PARALLEL_GROUP.contains(stage) && self.runners.get(stage).is_some_and(|r| r.total_steps(plan) > 0)
                })
                .collect();

            if parallel_runners.len() > 1 {
                if runtime.is_cancelled() || runtime.is_past_deadline() {
                    return RunnerOutcome::Cancelled;
                }

                // Each thread collects its steps independently.
                // Vec slot order matches parallel_runners order.
                let mut thread_results: Vec<Option<Vec<CollectedStep>>> =
                    (0..parallel_runners.len()).map(|_| None).collect();

                std::thread::scope(|s| {
                    let mut handles = Vec::with_capacity(parallel_runners.len());
                    for stage in &parallel_runners {
                        let runner = self.runners.get(stage).expect("runner present");
                        let cancel = runtime.cancel_token();
                        handles.push(s.spawn(move || runner.run_collecting(plan, cancel, tls_verifier)));
                    }
                    for (i, handle) in handles.into_iter().enumerate() {
                        // join() only fails if the thread panicked; propagate.
                        thread_results[i] = handle.join().expect("parallel runner thread panicked");
                    }
                });

                // Merge results back into runtime in stage_order sequence.
                for (stage, collected_opt) in parallel_runners.iter().zip(thread_results.into_iter()) {
                    parallel_done.insert(*stage);
                    let Some(steps) = collected_opt else {
                        // Runner signalled cancellation.
                        return RunnerOutcome::Cancelled;
                    };
                    for step in steps {
                        runtime.record_step(
                            plan,
                            step.phase,
                            step.message,
                            step.latest_probe_target,
                            step.latest_probe_outcome,
                            None,
                            step.artifacts,
                        );
                    }
                }
            }
        }

        for stage in &plan.stage_order {
            // Skip stages already handled by the parallel group.
            if parallel_done.contains(stage) {
                continue;
            }
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                return RunnerOutcome::Cancelled;
            }
            if runner.total_steps(plan) == 0 {
                continue;
            }
            match runner.run(plan, runtime, tls_verifier) {
                RunnerOutcome::Completed => {}
                RunnerOutcome::Cancelled => return RunnerOutcome::Cancelled,
                RunnerOutcome::Finished => return RunnerOutcome::Finished,
            }
        }
        RunnerOutcome::Completed
    }
}
