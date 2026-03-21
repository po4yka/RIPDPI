package com.poyka.ripdpi.services

internal interface ServiceClock {
    fun nowMillis(): Long
}

internal object SystemServiceClock : ServiceClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
