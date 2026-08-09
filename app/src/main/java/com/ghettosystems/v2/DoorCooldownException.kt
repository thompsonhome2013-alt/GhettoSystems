package com.ghettosystems.v2

class DoorCooldownException(
    val retryAfterSeconds: Int,
    message: String,
) : Exception(message)