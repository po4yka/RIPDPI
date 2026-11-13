package com.poyka.ripdpi.data

/** Empty data uses the default byte; nonempty data must fit one byte. */
fun isValidOobData(value: String): Boolean = value.length <= 1 && value.all { it.code <= UByte.MAX_VALUE.toInt() }
