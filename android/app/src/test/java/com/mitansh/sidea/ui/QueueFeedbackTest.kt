package com.mitansh.sidea.ui

import androidx.media3.session.SessionResult
import org.junit.Assert.*
import org.junit.Test

class QueueFeedbackTest {
    @Test fun `only confirmed enqueue success offers Open and failures and edits never do`() {
        assertEquals(QueueFeedback("Added to queue", true), queueResultFeedback(SessionResult.RESULT_SUCCESS, "Added to queue"))
        assertEquals(QueueFeedback("Will play next", true), queueResultFeedback(SessionResult.RESULT_SUCCESS, "Will play next"))
        assertNull(queueResultFeedback(SessionResult.RESULT_SUCCESS, null))
        for (code in listOf(SessionResult.RESULT_ERROR_BAD_VALUE, SessionResult.RESULT_ERROR_PERMISSION_DENIED,
            SessionResult.RESULT_ERROR_INVALID_STATE, SessionResult.RESULT_ERROR_UNKNOWN)) {
            val error = queueResultFeedback(code, "Added to queue")!!
            assertFalse(error.openQueue)
            assertNotEquals("Added to queue", error.message)
        }
    }
}
