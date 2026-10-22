package com.mitansh.sidea.playback

import androidx.media3.common.PlaybackException
import com.mitansh.sidea.data.youtube.PlaybackHttpException
import com.mitansh.sidea.data.youtube.UnsupportedAudioException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun wrappedUnavailableResponseIsNotMistakenForALostNetwork() {
        val error = IOException("resolver", IOException("wrapped", UnsupportedAudioException("not playable")))
        assertEquals(PlaybackFailureKind.UNAVAILABLE, PlaybackFailurePolicy.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error, null))
    }

    @Test fun badHttpStatusIsASourceFailureEvenThroughIOExceptionWrapping() {
        for (status in listOf(401, 403, 404, 410, 429, 500, 503)) {
            val error = IOException("open", IOException("resolver", PlaybackHttpException(status)))
            assertEquals(status, PlaybackFailurePolicy.httpStatus(error))
            assertEquals(PlaybackFailureKind.SOURCE, PlaybackFailurePolicy.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error, status))
        }
    }

    @Test fun timeoutIsDistinguishedFromUnsupportedRecordingAndDecoderFailure() {
        assertEquals(PlaybackFailureKind.CONNECTION, PlaybackFailurePolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, IOException(), null))
        assertEquals(PlaybackFailureKind.UNAVAILABLE, PlaybackFailurePolicy.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(IllegalArgumentException("bad id")), null))
        assertEquals(PlaybackFailureKind.DECODER, PlaybackFailurePolicy.classify(PlaybackException.ERROR_CODE_DECODING_FAILED, null, null))
    }

    @Test fun onlineSourceFailureRetriesInsteadOfWaitingForAConnectionEvent() {
        val action = PlaybackRecovery().plan(PlaybackFailureKind.SOURCE, true, "song", 2)
        assertTrue(action is PlaybackRecovery.Action.Retry)
        val ticket = (action as PlaybackRecovery.Action.Retry).ticket
        assertEquals("song", ticket.mediaId)
        assertEquals(2, ticket.index)
        assertEquals(500L, ticket.delayMs)
    }

    @Test fun repeatedOfflineCallbacksDoNotConsumeTheOnlineRetryBudget() {
        val recovery = PlaybackRecovery()
        repeat(5) { assertTrue(recovery.plan(PlaybackFailureKind.CONNECTION, false, "song", 2) is PlaybackRecovery.Action.WaitForNetwork) }
        assertTrue(recovery.plan(PlaybackFailureKind.CONNECTION, true, "song", 2) is PlaybackRecovery.Action.Retry)
        assertTrue(recovery.plan(PlaybackFailureKind.CONNECTION, true, "song", 2) is PlaybackRecovery.Action.Retry)
        assertEquals(PlaybackRecovery.Action.Stop, recovery.plan(PlaybackFailureKind.CONNECTION, true, "song", 2))
    }

    @Test fun retriesAreBoundedAndBackOffWithoutResettingOnPreparation() {
        val recovery = PlaybackRecovery()
        assertEquals(500L, recovery.retry("song", 0)!!.delayMs)
        assertEquals(1500L, recovery.retry("song", 0)!!.delayMs)
        repeat(10) { assertNull(recovery.retry("song", 0)) }
    }

    @Test fun pausingCancelsPendingRetryWithoutResettingTheFailureBudget() {
        val recovery = PlaybackRecovery()
        val ticket = recovery.retry("song", 0)!!
        recovery.cancel()
        assertFalse(recovery.isCurrent(ticket, "song", 0))
        assertEquals(1500L, recovery.retry("song", 0)!!.delayMs)
        assertNull(recovery.retry("song", 0))
    }

    @Test fun queueReplacementAndManualRetryInvalidateOldWorkEvenForTheSameSong() {
        val recovery = PlaybackRecovery()
        val old = recovery.retry("song", 0)!!
        recovery.reset()
        val fresh = recovery.retry("song", 0)!!
        assertFalse(recovery.isCurrent(old, "song", 0))
        assertTrue(recovery.isCurrent(fresh, "song", 0))
        assertEquals(500L, fresh.delayMs)
    }

    @Test fun duplicateSongOccurrencesAndSkippingCannotReceiveEachOthersRetries() {
        val recovery = PlaybackRecovery()
        val ticket = recovery.retry("same", 0)!!
        assertFalse(recovery.isCurrent(ticket, "same", 1))
        assertFalse(recovery.isCurrent(ticket, "other", 0))
        assertFalse(recovery.isCurrent(ticket, null, -1))
    }

    @Test fun unavailableRecordingDoesNotRetryOrSpendAnotherTracksBudget() {
        val recovery = PlaybackRecovery()
        for (online in listOf(true, false)) {
            assertEquals(PlaybackRecovery.Action.Stop, recovery.plan(PlaybackFailureKind.UNAVAILABLE, online, "song", 0))
        }
        assertEquals(500L, recovery.retry("other", 1)!!.delayMs)
    }

    @Test fun causeCyclesAreBoundedAndDoNotHangErrorReporting() {
        val one = IOException("one")
        val two = IOException("two")
        one.initCause(two)
        two.initCause(one)
        assertEquals(listOf(one, two), PlaybackFailurePolicy.causes(one))
    }
}
