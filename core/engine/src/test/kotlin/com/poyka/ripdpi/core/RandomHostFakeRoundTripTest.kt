package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RandomHostFakeRoundTripTest {
    @Test
    fun `native random mode survives recommendation editing storage and backup`() {
        for (random in listOf(true, false)) {
            val decoded = requireNotNull(decodeRipDpiProxyUiPreferences(nativeHostFakeConfig(random)))
            val parsed =
                parseStrategyChainDsl(
                    formatStrategyChainDsl(decoded.chains.tcpSteps, decoded.chains.udpSteps),
                ).getOrThrow()
            val edited =
                RipDpiProxyUIPreferences(
                    chains = decoded.chains.copy(tcpSteps = parsed.tcpSteps, udpSteps = parsed.udpSteps),
                )
            val stored =
                edited.applyStrategyToSettings(
                    AppSettings.getDefaultInstance(),
                    applyTcp = true,
                    applyQuic = false,
                )
            val restarted = AppSettings.parseFrom(stored.toByteArray())
            val restored = appSettingsFromJson(restarted.toJson())
            val projected =
                Json.parseToJsonElement(
                    RipDpiProxyUIPreferences.fromSettings(restored).toNativeConfigJson(),
                )
            val steps =
                projected.jsonObject
                    .getValue("chains")
                    .jsonObject
                    .getValue("tcpSteps")
                    .jsonArray

            assertEquals(if (random) JsonPrimitive(true) else null, steps.single().jsonObject["randomFakeHost"])
        }
    }

    private fun nativeHostFakeConfig(random: Boolean): String {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps = listOf(TcpChainStepModel(TcpChainStepKind.HostFake, "endhost")),
                    ),
            )
        val root = Json.parseToJsonElement(preferences.toNativeConfigJson()).jsonObject
        val chains = root.getValue("chains").jsonObject
        val step =
            chains
                .getValue("tcpSteps")
                .jsonArray
                .single()
                .jsonObject
        val steps = JsonArray(listOf(JsonObject(step + ("randomFakeHost" to JsonPrimitive(random)))))
        return JsonObject(root + ("chains" to JsonObject(chains + ("tcpSteps" to steps)))).toString()
    }
}
