package com.poyka.ripdpi.data

import com.google.protobuf.CodedOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppLockResetNoticePersistenceTest {
    @Test
    fun `bytes written before the flag existed decode with it off and other fields intact`() =
        runTest {
            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(encode { writeString(59, "hashed-pin") }))

            assertFalse(decoded.appLockResetNoticePending)
            assertEquals("hashed-pin", decoded.backupPin)
        }

    @Test
    fun `the flag is stored at proto field 425`() =
        runTest {
            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(encode { writeBool(425, true) }))

            assertTrue(decoded.appLockResetNoticePending)
        }

    private fun encode(write: CodedOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        CodedOutputStream.newInstance(out).apply {
            write()
            flush()
        }
        return out.toByteArray()
    }
}
