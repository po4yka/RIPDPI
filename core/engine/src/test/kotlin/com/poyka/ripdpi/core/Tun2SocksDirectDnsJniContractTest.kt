package com.poyka.ripdpi.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class Tun2SocksDirectDnsJniContractTest {
    @Test
    fun `direct DNS native methods are static exports on outer bindings class`() {
        assertStaticNative("jniRegisterDirectDnsSocketBinderNative", Any::class.java)
        assertStaticNative("jniUnregisterDirectDnsSocketBinderNative", Long::class.javaPrimitiveType!!)
    }

    private fun assertStaticNative(
        name: String,
        parameter: Class<*>,
    ) {
        val method = Tun2SocksNativeBindings::class.java.getDeclaredMethod(name, parameter)
        assertTrue("$name must match the outer-class Rust JNI export", Modifier.isStatic(method.modifiers))
        assertTrue("$name must remain native", Modifier.isNative(method.modifiers))
    }
}
