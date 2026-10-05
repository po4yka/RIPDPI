package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class TunKernelIdentityJniContractTest {
    @Test
    fun `identity read and constructor keep the exact JNI descriptor`() {
        val method =
            TunKernelIdentityNativeBindings::class.java.getDeclaredMethod(
                "jniReadTunKernelIdentity",
                Int::class.javaPrimitiveType,
            )
        assertTrue(Modifier.isNative(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertEquals(TunKernelIdentity::class.java, method.returnType)
        val constructor =
            TunKernelIdentity::class.java.getConstructor(String::class.java, Int::class.javaPrimitiveType)
        val identity = constructor.newInstance("private-tun-name", 17)
        assertEquals("private-tun-name", identity.interfaceName)
        assertEquals(17, identity.interfaceIndex)
        assertEquals("TunKernelIdentity(redacted)", identity.toString())
    }
}
