package com.mitansh.sidea.playback

import com.mitansh.sidea.core.Track

/** The production mutation seam intentionally has no transport, volume or playlist APIs. */
interface QueueMutationPort {
    val occurrences: List<QueueOccurrence>
    val currentIndex: Int
    fun insert(index: Int, track: Track, occurrence: QueueOccurrence)
    fun remove(index: Int)
    fun move(from: Int, to: Int)
    fun synchronizeOrder()
}

class QueueCommandDispatcher(private val port: QueueMutationPort, private val policy: QueueOrderPolicy) {
    enum class Result { SUCCESS, PERMISSION_DENIED, BAD_VALUE, STALE }

    fun dispatch(trusted: Boolean, args: Map<String, Any?>): Result {
        if (!trusted) return Result.PERMISSION_DENIED
        val request = QueueRequest.parse(true, args) ?: return Result.BAD_VALUE
        val entries = port.occurrences
        val current = port.currentIndex
        when (request) {
            is QueueRequest.Request.Enqueue -> port.insert(policy.insertion(entries, current, request.next), request.track,
                QueueOccurrence(origin = QueueOrigin.MANUAL, context = entries.getOrNull(current)?.context))
            is QueueRequest.Request.Remove -> {
                val indices = policy.targets(entries, current, request.current, request.ids) ?: return Result.STALE
                indices.sortedDescending().forEach(port::remove)
            }
            is QueueRequest.Request.Move -> {
                val (from, to) = policy.move(entries, current, request.current, request.id, request.target) ?: return Result.STALE
                port.move(from, to)
                policy.reordered(port.occurrences, port.currentIndex, entries[from].origin)
            }
        }
        policy.canonicalOrder(port.occurrences)
        port.synchronizeOrder()
        return Result.SUCCESS
    }
}
