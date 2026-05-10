package com.poyka.ripdpi.diagnostics.dpi

data class Tcp16Target(
    val id: String,
    val asn: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val sni: String?,
)
