package com.poyka.ripdpi.data.testing

import com.poyka.ripdpi.data.persistence.KeystoreProfileRecordCipher
import com.poyka.ripdpi.data.persistence.ProfileRecordCipher
import javax.crypto.SecretKey

internal class FixtureProfileCipher(
    key: SecretKey,
) : ProfileRecordCipher {
    private val delegate = KeystoreProfileRecordCipher({ key }, { key })

    @Volatile var beforeSeal: ((String, String) -> Unit)? = null

    @Volatile var beforeOpen: ((String, String) -> Unit)? = null

    override fun seal(
        namespace: String,
        id: String,
        plaintext: ByteArray,
    ): ByteArray {
        beforeSeal?.invoke(namespace, id)
        return delegate.seal(namespace, id, plaintext)
    }

    override fun open(
        namespace: String,
        id: String,
        ciphertext: ByteArray,
    ): ByteArray {
        beforeOpen?.invoke(namespace, id)
        return delegate.open(namespace, id, ciphertext)
    }
}
