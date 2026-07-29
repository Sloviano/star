package com.starlink.scanner.domain

/**
 * Typed failures from the dish gRPC client, so the ViewModel never sees a raw
 * `StatusRuntimeException`. The real gRPC client (Module 1) maps gRPC status codes onto these.
 */
sealed class StarlinkError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Endpoint not reachable yet (dish WiFi up but gRPC server not listening / wrong network). */
    class Unavailable(cause: Throwable? = null) : StarlinkError("Dish not reachable", cause)

    /** Call exceeded its deadline. */
    class Timeout(cause: Throwable? = null) : StarlinkError("Dish did not respond in time", cause)

    /** Method not implemented on this firmware (triggers the get_status fallback upstream). */
    class Unimplemented(cause: Throwable? = null) : StarlinkError("Not supported by this firmware", cause)

    /** Anything else. */
    class Unknown(cause: Throwable? = null) : StarlinkError("Unknown dish error", cause)
}
