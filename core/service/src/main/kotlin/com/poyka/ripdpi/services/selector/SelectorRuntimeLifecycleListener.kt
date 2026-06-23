package com.poyka.ripdpi.services.selector

/**
 * A selector-runtime component whose watch/probe loops should run only while a
 * foreground runtime is alive. The VPN service [start]s every bound listener in
 * `onCreate` and [stop]s them in `onDestroy`, so selector hot-reload and the
 * urltest prober follow the service lifecycle without the service knowing the
 * concrete (app-layer) implementations.
 *
 * Bound as a Hilt multibinding (`@IntoSet`): each independent selector loop
 * contributes one listener. [start] / [stop] must be idempotent.
 */
interface SelectorRuntimeLifecycleListener {
    fun start()

    fun stop()
}
