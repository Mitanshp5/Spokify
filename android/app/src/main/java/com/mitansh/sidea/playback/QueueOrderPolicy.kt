package com.mitansh.sidea.playback

enum class QueueOrigin { MANUAL, CONTEXT, RADIO }

data class QueueOccurrence(
    val id: String = java.util.UUID.randomUUID().toString(),
    val origin: QueueOrigin = QueueOrigin.CONTEXT,
    val context: String? = null,
)

/** Physical successors are the authoritative plan, including while shuffle is enabled.
 * The service installs UnshuffledShuffleOrder to make ExoPlayer execute this exact plan.
 * Only pending non-manual successors are randomized when shuffle is turned on. History
 * and current never move; consumed manual occurrences therefore cannot be resurrected.
 */
class QueueOrderPolicy {
    private var canonical = emptyList<String>()

    /** Occurrence order, never recording IDs. Old snapshots fall back to their saved plan. */
    fun restore(ids: List<String>) { canonical = ids.distinct() }

    fun canonicalOrder(entries: List<QueueOccurrence>): List<String> {
        val live = entries.map { it.id }
        canonical = canonical.filter { it in live } + live.filter { it !in canonical }
        return canonical.toList()
    }

    /** Adopt only the dragged provenance's pending displayed order. A manual drag cannot
     * accidentally make a shuffled context/radio order canonical. History/current stay put. */
    fun reordered(entries: List<QueueOccurrence>, current: Int, origin: QueueOrigin) {
        val pending = entries.drop((current + 1).coerceIn(0, entries.size))
            .filter { it.origin == origin }.map { it.id }
        val iterator = pending.iterator()
        canonical = canonicalOrder(entries).map { if (it in pending) iterator.next() else it }
    }

    fun planned(entries: List<QueueOccurrence>, current: Int, shuffle: Boolean,
        random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
        val source = canonicalOrder(entries)
        if (shuffle) return shuffled(entries, current, random)
        val start = (current + 1).coerceIn(0, entries.size)
        val pending = entries.drop(start)
        val sources = pending.filter { it.origin != QueueOrigin.MANUAL }.associateBy { it.id }
        return entries.take(start).map { it.id } + pending.filter { it.origin == QueueOrigin.MANUAL }.map { it.id } +
            listOf(QueueOrigin.CONTEXT, QueueOrigin.RADIO).flatMap { origin ->
                source.filter { sources[it]?.origin == origin }
            }
    }

    fun insertion(entries: List<QueueOccurrence>, current: Int, next: Boolean): Int {
        val start = (current + 1).coerceIn(0, entries.size)
        return if (next) start else start + entries.drop(start).takeWhile { it.origin == QueueOrigin.MANUAL }.size
    }

    fun shuffled(entries: List<QueueOccurrence>, current: Int, random: kotlin.random.Random): List<String> {
        val start = (current + 1).coerceIn(0, entries.size)
        val pending = entries.drop(start)
        return (entries.take(start) + pending.filter { it.origin == QueueOrigin.MANUAL } +
            pending.filter { it.origin == QueueOrigin.CONTEXT }.shuffled(random) +
            pending.filter { it.origin == QueueOrigin.RADIO }.shuffled(random)).map { it.id }
    }

    fun targets(entries: List<QueueOccurrence>, current: Int, anchor: String?, ids: List<String>): List<Int>? {
        if (anchor != entries.getOrNull(current)?.id || ids.isEmpty() || ids.distinct().size != ids.size) return null
        val indices = ids.map { id -> entries.indexOfFirst { it.id == id } }
        return indices.takeIf { it.all { index -> index > current && index in entries.indices } }
    }

    fun move(entries: List<QueueOccurrence>, current: Int, anchor: String?, id: String, target: String): Pair<Int, Int>? {
        val indices = targets(entries, current, anchor, listOf(id, target)) ?: return null
        return (indices[0] to indices[1]).takeIf { entries[it.first].origin == entries[it.second].origin }
    }
}

/** Strict, Android-free command boundary shared by the actual session dispatcher and tests. */
object QueueRequest {
    const val ACTION = "com.mitansh.sidea.QUEUE"
    const val ID = "queue_occurrence"
    const val ORIGIN = "queue_origin"
    sealed interface Request {
        data class Enqueue(val next: Boolean, val track: com.mitansh.sidea.core.Track) : Request
        data class Remove(val current: String, val ids: List<String>) : Request
        data class Move(val current: String, val id: String, val target: String) : Request
    }
    fun parse(trusted: Boolean, args: Map<String, Any?>): Request? {
        if (!trusted) return null
        fun text(key: String) = (args[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 }
        return when (args["op"]) {
            "next", "append" -> {
                if (args.keys != setOf("op", "id", "title", "artist", "duration", "artwork")) return null
                val duration = args["duration"] as? Long ?: return null
                if (duration < 0 || args["artwork"] !is String) return null
                val artist = (args["artist"] as? String)?.takeIf { it.length <= 4096 } ?: return null
                Request.Enqueue(args["op"] == "next", com.mitansh.sidea.core.Track(text("id") ?: return null,
                    text("title") ?: return null, artist, duration,
                    (args["artwork"] as String).takeIf { it.isNotEmpty() }))
            }
            "remove" -> {
                if (args.keys != setOf("op", "current", "ids")) return null
                val ids = (args["ids"] as? List<*>)?.map { (it as? String)?.takeIf(String::isNotBlank) ?: return null } ?: return null
                if (ids.isEmpty() || ids.size > 10000 || ids.distinct().size != ids.size) return null
                Request.Remove(text("current") ?: return null, ids)
            }
            "move" -> {
                if (args.keys != setOf("op", "current", "id", "target")) return null
                Request.Move(text("current") ?: return null, text("id") ?: return null, text("target") ?: return null)
            }
            else -> null
        }
    }
}
