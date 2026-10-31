package com.mitansh.sidea.ui

/** Live occurrence binding for queue-row playback; no recording-ID or stale-index guesses. */
internal fun queueSelectionTarget(
    shown: QueueSnapshot, entry: QueueEntry, currentIdentity: Any?, actualIdentities: List<Any>,
): Int? {
    if (shown.presentation.current?.instanceId != currentIdentity || entry !in shown.order) return null
    if (actualIdentities.distinct().size != actualIdentities.size) return null
    return actualIdentities.indexOf(entry.instanceId).takeIf { it >= 0 }
}
