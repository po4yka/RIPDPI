package com.poyka.ripdpi.data

/** RFC 7617 forbids a colon in the user-id and ASCII controls in either field. */
fun isValidNaiveCredentials(
    username: String?,
    password: String?,
): Boolean =
    !username.isNullOrBlank() && password != null &&
        username.none { it == ':' || it <= '\u001f' || it == '\u007f' } &&
        password.none { it <= '\u001f' || it == '\u007f' }
