package com.poyka.ripdpi.data

const val STARTED_BROADCAST = "com.poyka.ripdpi.STARTED"
const val STOPPED_BROADCAST = "com.poyka.ripdpi.STOPPED"
const val FAILED_BROADCAST = "com.poyka.ripdpi.FAILED"

const val SENDER = "sender"

enum class Sender(val senderName: String) {
    Proxy("Proxy"),
    VPN("VPN")
}
