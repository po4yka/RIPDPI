package com.poyka.ripdpi.data.persistence

/** Plain record access confined to one database transaction on an IO thread. */
internal interface ProfileRecordAccess {
    fun read(
        namespace: String,
        id: String,
    ): ByteArray?

    fun entries(namespace: String): Map<String, ByteArray>

    fun keys(namespace: String): Set<String>

    fun write(
        namespace: String,
        id: String,
        payload: ByteArray,
    )

    fun delete(
        namespace: String,
        id: String,
    )

    fun clear(namespace: String)
}
