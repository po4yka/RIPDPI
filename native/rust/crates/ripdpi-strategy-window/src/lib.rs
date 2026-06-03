//! TCP window-size desync strategies (`wsize`, `wssize`).
//!
//! A representative, minimal `ripdpi-strategy-*` implementation crate: it
//! implements [`DesyncStrategy`] for each strategy type and contributes a
//! [`StrategyFactory`] to `STRATEGY_FACTORIES` for each stable ID, so
//! `ripdpi-strategy-registry` resolves it by ID with no central match arm.
//! See `README.md` and `FEATURE_EXTENSION_GUIDE.md` §1.

#![forbid(unsafe_code)]

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyStepDescriptor, StrategyStepFactory, StrategyStepRegistration, StrategyVerdict,
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

fn make_wsize_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(WsizeStrategy::default())
}

fn make_wssize_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(WssizeStrategy::default())
}

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static WSIZE_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "wsize",
        label: "TCP window size",
        aliases: &[],
        required_tier: CapabilityTier::Tier1,
        required_capabilities: &[RuntimeCapability::TcpWindowClamp],
        needs_parameters: false,
        parameter_schema: "",
    },
    factory: StrategyStepFactory::Stateless(make_wsize_strategy),
};

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static WSSIZE_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "wssize",
        label: "TCP window scale size",
        aliases: &[],
        required_tier: CapabilityTier::Tier1,
        required_capabilities: &[RuntimeCapability::TcpWindowClamp],
        needs_parameters: false,
        parameter_schema: "",
    },
    factory: StrategyStepFactory::Stateless(make_wssize_strategy),
};

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
