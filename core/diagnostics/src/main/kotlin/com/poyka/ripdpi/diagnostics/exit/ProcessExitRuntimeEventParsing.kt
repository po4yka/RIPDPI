package com.poyka.ripdpi.diagnostics.exit

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.toKeyValueTokens

internal fun NativeSessionEventEntity.toKeyValueTokens(): Map<String, String> = message.toKeyValueTokens()
