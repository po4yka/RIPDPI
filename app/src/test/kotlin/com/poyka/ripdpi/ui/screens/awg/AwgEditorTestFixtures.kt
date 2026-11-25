package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.core.WarpKeyPair
import com.poyka.ripdpi.core.WarpKeyPairGenerator
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import java.util.Base64

internal val RtkSouthTestPreset =
    AwgCohortPreset(
        id = "rtk_south",
        displayNameKey = "awg_cohort_rtk_south_name",
        descriptionKey = "awg_cohort_rtk_south_desc",
        jc = 4,
        jmin = 40,
        jmax = 70,
        s1 = 50,
        s2 = 100,
        h1 = "1000000001",
        h2 = "1000000002",
        h3 = "1000000003",
        h4 = "1000000004",
        randomizeHeaders = false,
    )
internal val AwgTestCatalog = AwgCohortCatalogData(presets = listOf(RtkSouthTestPreset))

internal fun activatableAwgEditor(): AmneziaWgEditorState =
    AmneziaWgEditorState
        .initial()
        .updateField(AwgEditorField.SERVER, "vpn.example.com")
        .updateField(AwgEditorField.SERVER_PORT, "51820")
        .updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)
        .updateField(AwgEditorField.PEER_PUBLIC_KEY, AwgPeerTestKey)
        .updateField(AwgEditorField.ADDRESS, "10.8.0.2/32")

internal val AwgPrivateTestKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
internal val AwgPeerTestKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
internal val AwgGeneratedPrivateTestKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
internal val AwgGeneratedPublicTestKey = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })

internal class FakeWarpKeyPairGenerator : WarpKeyPairGenerator {
    var failure: Exception? = null
    var calls = 0

    override suspend fun generate(): WarpKeyPair {
        calls += 1
        failure?.let { throw it }
        return WarpKeyPair(AwgGeneratedPrivateTestKey, AwgGeneratedPublicTestKey)
    }
}
