//! TCP window-size desync strategies.

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyVerdict,
};

/// TCP window clamp strategy using a direct byte value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WsizeStrategy {
    window: u32,
}

impl WsizeStrategy {
    /// Creates a strategy that requests `TCP_WINDOW_CLAMP = window`.
    pub const fn new(window: u32) -> Self {
        Self { window }
    }
}

impl Default for WsizeStrategy {
    fn default() -> Self {
        Self { window: 4 }
    }
}

/// TCP window clamp strategy using a size and scale factor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WssizeStrategy {
    size: u32,
    scale: u8,
}

impl WssizeStrategy {
    /// Creates a strategy where the effective clamp is `size << scale`.
    pub const fn new(size: u32, scale: u8) -> Self {
        Self { size, scale }
    }

    /// Returns the effective clamp value.
    pub fn effective_window(self) -> u32 {
        self.size.checked_shl(u32::from(self.scale)).unwrap_or(u32::MAX)
    }
}

impl Default for WssizeStrategy {
    fn default() -> Self {
        Self { size: 64, scale: 2 }
    }
}

impl DesyncStrategy for WsizeStrategy {
    fn id(&self) -> &str {
        "wsize"
    }

    fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
        true
    }

    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        plan_window_clamp(ctx, plan, self.window)
    }

    fn describe(&self) -> StrategyDescriptor {
        descriptor("wsize", "TCP window size")
    }
}

impl DesyncStrategy for WssizeStrategy {
    fn id(&self) -> &str {
        "wssize"
    }

    fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
        true
    }

    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        plan_window_clamp(ctx, plan, self.effective_window())
    }

    fn describe(&self) -> StrategyDescriptor {
        descriptor("wssize", "TCP window scale size")
    }
}

/// Returns a boxed built-in window strategy for a stable ID.
pub fn strategy_by_id(id: &str) -> Option<Box<dyn DesyncStrategy>> {
    match id {
        "wsize" => Some(Box::new(WsizeStrategy::default())),
        "wssize" => Some(Box::new(WssizeStrategy::default())),
        _ => None,
    }
}

fn plan_window_clamp(ctx: &StrategyContext<'_>, plan: &mut DesyncPlan, window: u32) -> Result<(), StrategyError> {
    if !ctx.caps.has(RuntimeCapability::TcpWindowClamp) {
        return Ok(());
    }
    plan.actions.push(DesyncAction::SetWindowClamp(window));
    plan.verdict = StrategyVerdict::Apply;
    Ok(())
}

fn descriptor(id: &str, label: &str) -> StrategyDescriptor {
    StrategyDescriptor {
        id: id.to_owned(),
        label: label.to_owned(),
        supported_protocols: Vec::new(),
        required_tier: CapabilityTier::Tier1,
        required_capabilities: vec![RuntimeCapability::TcpWindowClamp],
    }
}
