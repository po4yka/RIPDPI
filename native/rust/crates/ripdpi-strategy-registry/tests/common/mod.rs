use ripdpi_strategy_trait::{
    DesyncAction, DesyncPlan, DesyncStrategy, StrategyContext, StrategyDescriptor, StrategyError,
};

#[derive(Debug)]
pub struct StubStrategy {
    id: &'static str,
    result: Result<(), StrategyError>,
    action: Option<DesyncAction>,
}

#[allow(dead_code)]
impl StubStrategy {
    pub fn success(id: &'static str, action: DesyncAction) -> Self {
        Self { id, result: Ok(()), action: Some(action) }
    }

    pub fn failure(id: &'static str) -> Self {
        Self { id, result: Err(StrategyError::Execution("planned failure".to_owned())), action: None }
    }
}

impl DesyncStrategy for StubStrategy {
    fn id(&self) -> &str {
        self.id
    }

    fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
        true
    }

    fn plan(&self, _ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        if let Some(action) = &self.action {
            plan.actions.push(action.clone());
        }
        self.result.clone()
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor { id: self.id.to_owned(), label: self.id.to_owned(), ..StrategyDescriptor::default() }
    }
}
