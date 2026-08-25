package com.poyka.ripdpi.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalesConfigTest {
    @Test
    fun `parse returns the ten BCP-47 tags registered in locales_config xml`() {
        val tags = LocalesConfig.parse(RuntimeEnvironment.getApplication())
        assertEquals(listOf("en", "ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR"), tags)
    }
}
