package com.starlink.scanner.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Dish connection state as shown in the persistent status strip. */
enum class DishConnection { NONE, SEARCHING, CONNECTED }

/**
 * Tiny process-scoped holder for cross-screen UI state. The Capture flow writes
 * [dishConnection]; the app shell's status strip reads it on every screen.
 */
object SessionState {
    val dishConnection = MutableStateFlow(DishConnection.NONE)
}
