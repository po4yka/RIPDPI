import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluggableTransportGoLinkerFlagsTest {
    @Test
    fun `existing sources and receipts keep the default linker arguments`() {
        val flags = pluggableTransportGoLinkerFlags(emptyMap<String, Any>())
        assertEquals(emptyList(), flags)
        assertEquals("-ldflags=-s -w -buildid=", pluggableTransportGoLinkerArgument(flags))
        verifyPluggableTransportGoLinkerFlags(emptyMap<String, Any>(), flags, "lyrebird")
        verifyPluggableTransportGoLinkerFlags(mapOf("goLinkerFlags" to emptyList<String>()), flags, "cloudflared")
    }

    @Test
    fun `source specific flags reach the linker and must match the receipt`() {
        val flags = pluggableTransportGoLinkerFlags(mapOf("goLinkerFlags" to listOf("-checklinkname=0")))
        assertEquals("-ldflags=-s -w -buildid= -checklinkname=0", pluggableTransportGoLinkerArgument(flags))
        verifyPluggableTransportGoLinkerFlags(mapOf("goLinkerFlags" to flags), flags, "snowflake")
        listOf(emptyMap(), mapOf("goLinkerFlags" to emptyList()), mapOf("goLinkerFlags" to listOf("-checklinkname=1")))
            .forEach { stale ->
                assertFailsWith<IllegalArgumentException> {
                    verifyPluggableTransportGoLinkerFlags(stale, flags, "snowflake")
                }
            }
        assertFailsWith<IllegalArgumentException> {
            verifyPluggableTransportGoLinkerFlags(mapOf("goLinkerFlags" to flags), emptyList(), "lyrebird")
        }
    }

    @Test
    fun `malformed linker flags fail instead of silently dropping build inputs`() {
        listOf("-checklinkname=0", listOf(1), listOf(""), listOf("-s -w")).forEach { malformed ->
            assertFailsWith<IllegalArgumentException> {
                pluggableTransportGoLinkerFlags(mapOf("goLinkerFlags" to malformed))
            }
        }
    }
}
