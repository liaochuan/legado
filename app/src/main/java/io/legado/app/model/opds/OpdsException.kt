package io.legado.app.model.opds

enum class OpdsError {
    UNAUTHORIZED,
    UNAUTHORIZED_REDIRECT,
    FORBIDDEN,
    NOT_FOUND,
    NOT_OPDS,
    NETWORK,
    SERVER
}

class OpdsException(
    val error: OpdsError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
