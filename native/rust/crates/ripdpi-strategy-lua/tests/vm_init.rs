#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;

#[test]
fn lua_vm_initializes() {
    assert!(LuaStrategyEngine::new().is_ok());
}

#[test]
fn clock_gettime_returns_seconds_and_subsecond_nanoseconds() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "clock",
            br#"
                function clock_arity() return select('#', clock_gettime()) end
                function clock_seconds() local seconds = clock_gettime(); return seconds end
                function clock_nanoseconds() local _, nanos = clock_gettime(); return nanos end
            "#,
        )
        .expect("load clock probes");

    let before = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("system time").as_secs();
    assert_eq!(engine.call_i64("clock_arity").expect("clock arity"), 2);
    let seconds = engine.call_i64("clock_seconds").expect("clock seconds");
    let nanos = engine.call_i64("clock_nanoseconds").expect("clock nanoseconds");
    let after = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("system time").as_secs();

    assert!((before..=after).contains(&u64::try_from(seconds).expect("nonnegative clock seconds")));
    assert!((0..1_000_000_000).contains(&nanos), "nanoseconds must be within one second");
}
