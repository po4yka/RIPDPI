#![cfg(feature = "lua-strategies")]

//! Regression tests for the base-library hardening.
//!
//! `mlua` loads `luaopen_base` unconditionally, independently of the `StdLib`
//! mask, so `dofile`/`loadfile` (arbitrary file read+execute) survive the
//! `os`/`io`/`package` exclusion and would bypass the `load_script*` path jail
//! from inside any loaded script. The engine removes them. `load` is retained
//! but forced to text-only mode: the stock `load` accepts binary bytecode
//! (`mode="bt"`), an independent memory-unsafety escape, so a text-only wrapper
//! keeps the bundled pack working while refusing bytecode.

use ripdpi_strategy_lua::LuaStrategyEngine;

#[test]
fn loaded_script_cannot_reach_dofile_or_loadfile() {
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    engine
        .load_bytes(
            "base_lib_probe",
            b"function dofile_is_nil() if dofile == nil then return 1 else return 0 end end \
              function loadfile_is_nil() if loadfile == nil then return 1 else return 0 end end \
              function load_is_present() if load ~= nil then return 1 else return 0 end end",
        )
        .expect("probe script should load");

    assert_eq!(engine.call_i64("dofile_is_nil").expect("dofile_is_nil"), 1, "dofile must be removed from the sandbox");
    assert_eq!(
        engine.call_i64("loadfile_is_nil").expect("loadfile_is_nil"),
        1,
        "loadfile must be removed from the sandbox"
    );
    assert_eq!(
        engine.call_i64("load_is_present").expect("load_is_present"),
        1,
        "load (string compile) is retained for the bundled zapret pack"
    );
}

#[test]
fn calling_loadfile_from_a_script_fails_to_load() {
    // A script that actually tries to read a file via loadfile must fail to
    // execute (loadfile is nil) rather than reading the path off-disk.
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    let error = engine
        .load_bytes("loadfile_escape", b"local handle = loadfile('/etc/hosts')")
        .expect_err("calling loadfile must fail because it is removed");
    let message = error.to_string();
    assert!(message.contains("attempt to call a nil value"), "expected a nil-call error for loadfile, got: {message}");
}

#[test]
fn loaded_script_cannot_execute_bytecode_via_load() {
    // `load` is retained for the bundled pack but must refuse bytecode: a script
    // that dumps a function to bytecode and tries to `load` it back must get nil
    // (text-only mode), so attacker-crafted bytecode can never run.
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    engine
        .load_bytes(
            "bytecode_probe",
            b"local bytecode = string.dump(function() return 4242 end) \
              local reloaded = load(bytecode) \
              function bytecode_was_rejected() if reloaded == nil then return 1 else return 0 end end \
              function text_load_still_works() local f = load('return 7'); if f ~= nil then return f() else return -1 end end",
        )
        .expect("probe script should load (text source)");

    assert_eq!(
        engine.call_i64("bytecode_was_rejected").expect("bytecode_was_rejected"),
        1,
        "load() must refuse binary bytecode chunks"
    );
    assert_eq!(
        engine.call_i64("text_load_still_works").expect("text_load_still_works"),
        7,
        "load() must still compile text source for the bundled pack"
    );
}

#[test]
fn calling_dofile_from_a_script_fails_to_load() {
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    let error = engine
        .load_bytes("dofile_escape", b"dofile('/etc/hosts')")
        .expect_err("calling dofile must fail because it is removed");
    let message = error.to_string();
    assert!(message.contains("attempt to call a nil value"), "expected a nil-call error for dofile, got: {message}");
}
