package com.dpad.messaging.models

/** A reaction shown on a message bubble, derived from a reaction fallback SMS/MMS. */
data class MessageReaction(
    val emoji: String,
    /** Contact name or number of the person who reacted; unused when [isMine]. */
    val senderName: String,
    val isMine: Boolean,
    val date: Long
)
