package com.mitansh.sidea.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/** Shuffle randomness is applied to pending context/radio occurrences by QueueOrderPolicy.
 * Both timeline modes must then execute that physical plan, not randomly insert manual songs.
 * This order also appends newly fetched radio at the end rather than randomizing its placement.
 * Changing the order never changes shuffleModeEnabled, current item, transport or position.
 */
@UnstableApi
fun queueShuffleOrder(count: Int): ShuffleOrder = ShuffleOrder.UnshuffledShuffleOrder(count)
