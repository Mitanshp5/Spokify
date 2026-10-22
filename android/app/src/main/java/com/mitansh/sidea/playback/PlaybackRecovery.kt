package com.mitansh.sidea.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.mitansh.sidea.data.youtube.PlaybackHttpException
import com.mitansh.sidea.data.youtube.UnsupportedAudioException
import java.io.IOException

internal enum class PlaybackFailureKind { CONNECTION, SOURCE, UNAVAILABLE, DECODER, OTHER }

internal object PlaybackFailurePolicy {
    fun causes(error: Throwable): List<Throwable> = buildList {
        var next: Throwable? = error
        while (size < 12) {
            val current = next ?: break
            if (any { it === current }) break
            add(current)
            next = current.cause
        }
    }

    fun httpStatus(error: Throwable): Int? = causes(error).firstNotNullOfOrNull {
        when (it) {
            is HttpDataSource.InvalidResponseCodeException -> it.responseCode
            is PlaybackHttpException -> it.status
            else -> null
        }
    }

    fun kind(error: PlaybackException): PlaybackFailureKind = classify(error.errorCode, error.cause, httpStatus(error))

    internal fun classify(code: Int, cause: Throwable?, httpStatus: Int?): PlaybackFailureKind {
        val nested = cause?.let(::causes).orEmpty()
        if (nested.any { it is UnsupportedAudioException || it is IllegalArgumentException } ||
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) return PlaybackFailureKind.UNAVAILABLE
        if (httpStatus != null) return PlaybackFailureKind.SOURCE
        if (code in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) return PlaybackFailureKind.CONNECTION
        if (code in 4000..4999) return PlaybackFailureKind.DECODER
        if (code in 2000..3999 || nested.any { it is IOException }) return PlaybackFailureKind.SOURCE
        return PlaybackFailureKind.OTHER
    }

    fun message(error: PlaybackException): String = when (kind(error)) {
        PlaybackFailureKind.CONNECTION -> "Couldn't connect to the audio source. Check your connection and retry."
        PlaybackFailureKind.UNAVAILABLE -> "This recording isn't available to play. Try another result for this song."
        PlaybackFailureKind.DECODER -> "This recording couldn't be decoded. Try another result for this song."
        PlaybackFailureKind.SOURCE -> "Couldn't load this recording. Retry to refresh its audio source."
        PlaybackFailureKind.OTHER -> "Playback stopped. Retry this song."
    }
}

/** Each delayed action belongs to one queue generation and one occurrence of a recording. */
internal class PlaybackRecovery {
    data class Ticket(val generation: Long, val mediaId: String, val index: Int, val delayMs: Long)
    sealed interface Action {
        data class WaitForNetwork(val ticket: Ticket) : Action
        data class Retry(val ticket: Ticket) : Action
        data object Stop : Action
    }
    private var generation = 0L
    private var attempts = 0

    fun reset() { generation++; attempts = 0 }
    fun cancel() { generation++ }
    fun checkpoint(mediaId: String, index: Int) = Ticket(generation, mediaId, index, 0)
    fun isCurrent(ticket: Ticket, mediaId: String?, index: Int): Boolean =
        ticket.generation == generation && ticket.mediaId == mediaId && ticket.index == index

    fun plan(kind: PlaybackFailureKind, online: Boolean, mediaId: String, index: Int): Action {
        if (kind !in setOf(PlaybackFailureKind.CONNECTION, PlaybackFailureKind.SOURCE)) return Action.Stop
        if (!online) return Action.WaitForNetwork(checkpoint(mediaId, index))
        return retry(mediaId, index)?.let(Action::Retry) ?: Action.Stop
    }

    fun retry(mediaId: String, index: Int): Ticket? {
        if (attempts >= 2) return null
        generation++
        val delay = if (attempts++ == 0) 500L else 1500L
        return Ticket(generation, mediaId, index, delay)
    }
}
