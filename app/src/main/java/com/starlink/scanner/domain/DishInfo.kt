package com.starlink.scanner.domain

/**
 * Dish identity read from the dish's local gRPC API. Only the dish ID (UT ID) is retained — it is
 * the sole dish field the app records, alongside the scanned kit number and dish serial.
 */
data class DishInfo(
    val dishId: String,
)
