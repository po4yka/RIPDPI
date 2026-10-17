package com.poyka.ripdpi.benchmark

import android.os.Bundle
import android.os.Trace
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.activities.HomePauseViewModel
import com.poyka.ripdpi.activities.LogsViewModel
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityActions
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityScreen
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** A deterministic driver around production screens. It never starts a service or a probe. */
@AndroidEntryPoint
class ResponsivenessActivity : ComponentActivity() {
    @Inject lateinit var serviceStateStore: ServiceStateStore

    private val eventReads = AtomicLong()
    private val events = CountingEvents(List(RuntimeEventCount, ::runtimeEvent), eventReads)
    private var tick by mutableIntStateOf(0)
    private var phase by mutableStateOf("preparing")
    private var catalog by mutableStateOf(catalogInput(100))
    private lateinit var logsViewModel: LogsViewModel
    private lateinit var workload: String
    private lateinit var sourceToken: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workload = requireNotNull(intent.getStringExtra("workload"))
        require(workload in setOf("logs", "catalog", "telemetry"))
        sourceToken = requireNotNull(intent.getStringExtra("source"))
        val size = intent.getIntExtra("size", 100)
        require(size in setOf(100, 1000, 5000))
        catalog = catalogInput(size)
        logsViewModel = ViewModelProvider(this)[LogsViewModel::class.java]
        serviceStateStore.updateTelemetry(snapshot(0))
        setContent {
            RipDpiTheme {
                Column(Modifier.fillMaxSize().ripDpiAutomationTreeRoot()) {
                    DriverControls()
                    Workload(Modifier.weight(1f))
                }
            }
        }
        lifecycleScope.launch {
            withTimeout(10_000) {
                if (workload == "logs") logsViewModel.uiState.first { it.logs.size == RuntimeEventCount }
            }
            phase = "ready"
        }
    }

    @Composable
    private fun DriverControls() {
        // Fixed test labels are driver metadata, outside the product's locale resources.
        Text(
            text = "$workload/$phase/tick=$tick/size=${catalog.profiles.size}",
            modifier = Modifier.ripDpiTestTag("responsiveness-status"),
        )
        Button(
            onClick = ::runBurst,
            enabled = phase == "ready",
            modifier = Modifier.ripDpiTestTag("responsiveness-run"),
        ) { Text("Run deterministic burst") }
    }

    @Composable
    private fun Workload(modifier: Modifier) {
        when (workload) {
            "logs" -> {
                LogsRoute({}, {}, modifier, logsViewModel)
            }

            "catalog" -> {
                ProfileUtilityScreen(
                    state = catalog,
                    onBack = {},
                    actions = ProfileUtilityActions({ _, _, _ -> }, {}, {}, {}, {}, {}, {}),
                    modifier = modifier,
                )
            }

            "telemetry" -> {
                RipDpiNavHost(
                    mainViewModel = ViewModelProvider(this)[MainViewModel::class.java],
                    pauseViewModel = ViewModelProvider(this)[HomePauseViewModel::class.java],
                    startDestination = Route.Settings,
                    modifier = modifier,
                )
            }
        }
    }

    private fun runBurst() {
        check(phase == "ready")
        phase = "running"
        eventReads.set(0)
        lifecycleScope.launch {
            Trace.beginAsyncSection("RIPDPI:counterBurst", 1)
            try {
                repeat(CounterTicks) { index ->
                    tick = index + 1
                    Trace.beginSection("RIPDPI:counterDispatch")
                    try {
                        if (workload == "catalog") {
                            // Change an independent input while retaining the exact catalog instance.
                            catalog = catalog.copy(probeUrl = "https://benchmark.invalid/check?tick=$tick")
                        } else {
                            serviceStateStore.updateTelemetry(snapshot(tick))
                        }
                    } finally {
                        Trace.endSection()
                    }
                    delay(CounterTickMillis)
                }
                val counterReads = eventReads.get()
                if (workload == "logs") {
                    serviceStateStore.updateTelemetry(snapshot(tick, terminal = true))
                    withTimeout(10_000) {
                        logsViewModel.uiState.first { state ->
                            state.logs.size == RuntimeEventCount &&
                                state.logs.any { it.message.contains("Benchmark event $RuntimeEventCount") }
                        }
                    }
                }
                Log.i(
                    "RipDpiResponsiveness",
                    "source=$sourceToken workload=$workload size=${catalog.profiles.size} " +
                        "ticks=$tick counterEventReads=$counterReads finalEventReads=${eventReads.get()} " +
                        "outputRows=${logsViewModel.uiState.value.logs.size}",
                )
                phase = "done"
            } finally {
                Trace.endAsyncSection("RIPDPI:counterBurst", 1)
            }
        }
    }

    private fun snapshot(
        counter: Int,
        terminal: Boolean = false,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    totalSessions = counter.toLong(),
                    capturedAt = FixtureEpochMillis + counter,
                    nativeEvents =
                        if (terminal) {
                            CountingEvents(List(RuntimeEventCount + 1, ::runtimeEvent), eventReads)
                        } else {
                            events
                        },
                ),
            updatedAt = FixtureEpochMillis + counter,
        )
}
