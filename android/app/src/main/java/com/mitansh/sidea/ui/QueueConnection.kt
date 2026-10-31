package com.mitansh.sidea.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.playback.QueueRequest

/** Acknowledgements, not optimistic UI taps, produce success feedback. */
class QueueConnection(private val controller: MediaController?, private val feedback: (QueueFeedback) -> Unit) {
    fun enqueue(track: Track, next: Boolean) = send(Bundle().apply {
        putString("op", if (next) "next" else "append")
        putString("id", track.id); putString("title", track.title); putString("artist", track.artist)
        putLong("duration", track.durationMs.coerceAtLeast(0)); putString("artwork", track.artworkUrl.orEmpty())
    }, if (next) "Will play next" else "Added to queue")

    fun edit(snapshot: QueueSnapshot, action: QueueAction) {
        if (action is QueueAction.Select) {
            val player = controller
            if (player == null || !player.isConnected) {
                feedback(QueueFeedback("Queue disconnected. Reopen the player."))
                return
            }
            val identities = (0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).mediaMetadata.extras?.getString(QueueRequest.ID).orEmpty()
            }
            val current = player.currentMediaItem?.mediaMetadata?.extras?.getString(QueueRequest.ID)
            val index = queueSelectionTarget(snapshot, action.entry, current, identities)
            if (index == null) {
                feedback(QueueFeedback("Queue changed. Select the song again."))
                return
            }
            if (index != player.currentMediaItemIndex) player.seekTo(index, 0L)
            if (player.playerError != null || player.playbackState == androidx.media3.common.Player.STATE_IDLE) player.prepare()
            player.play()
            return
        }
        val current = snapshot.presentation.current?.instanceId as? String ?: return
        val commands = queueCommands(snapshot, snapshot, action)
        if (commands.isEmpty()) return
        val args = Bundle().apply { putString("current", current) }
        when (val command = commands.first()) {
            is QueueCommand.Select -> return // Current transport is supplied directly to QueueSheet.
            is QueueCommand.Move -> {
                args.putString("op", "move")
                args.putString("id", snapshot.order.first { it.index == command.from }.instanceId as? String ?: return)
                args.putString("target", snapshot.order.first { it.index == command.to }.instanceId as? String ?: return)
            }
            is QueueCommand.Remove -> {
                args.putString("op", "remove")
                args.putStringArrayList("ids", ArrayList(commands.map {
                    val index = (it as QueueCommand.Remove).index
                    snapshot.order.first { entry -> entry.index == index }.instanceId as? String ?: return
                }))
            }
        }
        send(args)
    }

    private fun send(args: Bundle, success: String? = null) {
        val player = controller
        if (player == null || !player.isConnected) { feedback(QueueFeedback("Queue disconnected. Reopen the player.")); return }
        try {
            val future = player.sendCustomCommand(SessionCommand(QueueRequest.ACTION, Bundle.EMPTY), args)
            future.addListener({
                try {
                    queueResultFeedback(future.get().resultCode, success)?.let(feedback)
                } catch (_: Exception) { feedback(QueueFeedback("Couldn't update queue. Try again.")) }
            }, { runnable -> Handler(Looper.getMainLooper()).post(runnable) })
        } catch (_: Exception) { feedback(QueueFeedback("Couldn't contact queue. Try again.")) }
    }
}
