package com.mitansh.sidea.data.offline

/** Bytes currently attributable to automatic (frequent-play) downloads. */
data class AutomaticUsage(
    /** Fully downloaded automatic content, the bytes the cap must never exceed. */
    val storedBytes: Long = 0L,
    /** Bytes already written for automatic downloads that are still active. */
    val inFlightBytes: Long = 0L,
    /** Automatic downloads accepted but not yet started; each reserves [AutomaticDownloadPolicy.RESERVATION_BYTES]. */
    val pendingCount: Int = 0,
    /** All tracked automatic downloads, including terminal and pending ones. */
    val trackedCount: Int = 0,
)

/**
 * Pure policy for frequent-play downloads: when to queue, how much storage they may reserve, and
 * what turning the setting off is allowed to cancel. Kept free of Android APIs so it is unit tested.
 */
object AutomaticDownloadPolicy {

    /** Qualified listens of the same durable track before it is auto-queued. */
    const val QUALIFIED_PLAYS = 3

    /** Default automatic content budget, used until the user picks another one. */
    const val CAP_BYTES = 500L * 1024 * 1024

    /** Bounded size reserved for an automatic item whose real length is not known yet. */
    const val RESERVATION_BYTES = 4L * 1024 * 1024

    /** Upper bound on tracked automatic downloads so the queue can never grow without limit. */
    const val MAX_TRACKED_AUTOMATIC = 25

    fun isQualified(playCount: Int): Boolean = playCount >= QUALIFIED_PLAYS

    /** After removing an automatic item it stays suppressed until a later listen raises the count. */
    fun canRequeueOnPlay(playCount: Int, suppressedAtPlayCount: Int): Boolean =
        suppressedAtPlayCount <= 0 || playCount > suppressedAtPlayCount

    fun projectedBytes(usage: AutomaticUsage, incomingBytes: Long = RESERVATION_BYTES): Long =
        usage.storedBytes + usage.inFlightBytes + usage.pendingCount * RESERVATION_BYTES + incomingBytes

    /**
     * Whether another automatic item fits inside the user's budget.
     *
     * The budget and the incoming size are separate values and are named, so a call cannot pass
     * one where the other belongs. A budget is a ceiling; the incoming size is what the new item
     * is expected to add.
     */
    fun canQueue(
        usage: AutomaticUsage,
        capBytes: Long,
        incomingBytes: Long = RESERVATION_BYTES,
    ): Boolean =
        usage.trackedCount < MAX_TRACKED_AUTOMATIC && projectedBytes(usage, incomingBytes) <= capBytes

    /** Turning automatic downloads off cancels pending work; it never deletes bytes or manual jobs. */
    fun shouldCancelOnDisable(state: Int, automatic: Boolean): Boolean =
        automatic && state in setOf(
            DownloadState.STATE_QUEUED,
            DownloadState.STATE_STOPPED,
            DownloadState.STATE_DOWNLOADING,
            DownloadState.STATE_RESTARTING,
        )
}
