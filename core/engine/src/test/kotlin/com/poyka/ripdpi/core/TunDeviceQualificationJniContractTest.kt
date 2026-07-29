package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class TunDeviceQualificationJniContractTest {
    @Test
    fun `qualification probe keeps its instance JNI signature`() {
        val method =
            TunDeviceQualificationNativeBindings::class.java.getDeclaredMethod(
                "jniProbeUnprivilegedBindToDevice",
            )

        assertTrue("probe must remain native", Modifier.isNative(method.modifiers))
        assertFalse("Rust export accepts jobject, not jclass", Modifier.isStatic(method.modifiers))
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }
}
