package com.mitansh.sidea.ui

import androidx.media3.session.SessionResult

data class QueueFeedback(val message: String, val openQueue: Boolean = false)

/** Only a successful enqueue acknowledgement is actionable; edits remain quiet. */
fun queueResultFeedback(code: Int, enqueueMessage: String?): QueueFeedback? = when (code) {
    SessionResult.RESULT_SUCCESS -> enqueueMessage?.let { QueueFeedback(it, openQueue = true) }
    SessionResult.RESULT_ERROR_INVALID_STATE -> QueueFeedback("Queue changed. Select the songs again.")
    SessionResult.RESULT_ERROR_PERMISSION_DENIED -> QueueFeedback("Queue access denied. Reopen the player.")
    else -> QueueFeedback("Couldn't update queue (code $code). Try again.")
}
