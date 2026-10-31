package com.mitansh.sidea.ui

// Timeline UIDs may be arbitrary objects, which cannot be used directly as saveable lazy keys.
class QueueRowKeys {
    private val keys = mutableMapOf<Any, Long>()
    private var nextKey = 0L
    fun key(instanceId: Any): Long = keys.getOrPut(instanceId) { nextKey++ }
}

data class QueueSnapshot(
    val order: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val shuffled: Boolean = false,
) {
    val presentation get() = QueuePresentation.project(order, currentIndex)
}

sealed interface QueueAction {
    data class Select(val entry: QueueEntry) : QueueAction
    data class Remove(val entry: QueueEntry) : QueueAction
    data class Move(val entry: QueueEntry, val up: Boolean) : QueueAction
    data object ClearUpcoming : QueueAction
}

sealed interface QueueCommand {
    data class Select(val index: Int, val seek: Boolean) : QueueCommand
    data class Remove(val index: Int) : QueueCommand
    data class Move(val from: Int, val to: Int) : QueueCommand
}

// Small seams keep ordering and mutation policy testable without an Android controller.
fun queuePlaybackIndices(count: Int, first: Int, next: (Int) -> Int): List<Int> {
    val indices = mutableListOf<Int>()
    val seen = mutableSetOf<Int>()
    var index = first
    while (index in 0 until count && seen.add(index)) {
        indices += index
        index = next(index)
    }
    return indices
}

fun queueCommands(expected: QueueSnapshot, actual: QueueSnapshot, action: QueueAction): List<QueueCommand> {
    // Reject taps from a stale composition rather than applying a shifted index to another song.
    if (expected != actual) return emptyList()
    val presentation = actual.presentation
    return when (action) {
        is QueueAction.Select -> if (action.entry in actual.order) {
            listOf(QueueCommand.Select(action.entry.index, action.entry.index != actual.currentIndex))
        } else emptyList()
        is QueueAction.Remove -> if (action.entry in presentation.upcoming) {
            listOf(QueueCommand.Remove(action.entry.index))
        } else emptyList()
        is QueueAction.Move -> {
            val position = presentation.upcoming.indexOf(action.entry)
            val target = position + if (action.up) -1 else 1
            if (actual.shuffled || position < 0 || target !in presentation.upcoming.indices) emptyList()
            else listOf(QueueCommand.Move(action.entry.index, presentation.upcoming[target].index))
        }
        QueueAction.ClearUpcoming -> presentation.upcoming.sortedByDescending { it.index }.map { QueueCommand.Remove(it.index) }
    }
}

data class QueuePresentation(
    val current: QueueEntry? = null,
    val upcoming: List<QueueEntry> = emptyList(),
) {
    companion object {
        fun project(playbackOrder: List<QueueEntry>, currentIndex: Int): QueuePresentation {
            val position = playbackOrder.indexOfFirst { it.index == currentIndex }
            if (position < 0) return QueuePresentation()
            return QueuePresentation(playbackOrder[position], playbackOrder.drop(position + 1))
        }
    }
}
