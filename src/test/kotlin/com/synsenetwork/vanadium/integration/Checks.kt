package com.synsenetwork.vanadium.integration

/** Live checks fail the server start with a plain assertion message. */
internal fun check(ok: Boolean, message: () -> String) {
    if (!ok) throw AssertionError(message())
}

internal fun check(ok: Boolean, message: String) = check(ok) { message }
