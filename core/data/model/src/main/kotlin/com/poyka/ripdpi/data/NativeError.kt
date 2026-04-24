package com.poyka.ripdpi.data

import java.io.IOException

sealed interface NativeError {
    class AlreadyRunning(
        component: String,
    ) : IllegalStateException("$component is already running"),
        NativeError

    class NotRunning(
        component: String,
    ) : IllegalStateException("$component is not running"),
        NativeError

    class SessionCreationFailed(
        component: String,
    ) : Exception("Native $component session was not created"),
        NativeError

    class NativeIoError(
        message: String,
        cause: IOException,
    ) : IOException(message, cause),
        NativeError
}
