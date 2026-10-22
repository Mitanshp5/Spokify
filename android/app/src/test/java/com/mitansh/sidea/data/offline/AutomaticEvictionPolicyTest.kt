package com.mitansh.sidea.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks which automatic content is removed when the budget is reduced.
 *
 * The budget is a ceiling on the bytes automatic content occupies, so reducing it has to remove
 * content that was already written, not only work that has not started.
 */
class AutomaticEvictionPolicyTest {

    private val megabyte = 1024L * 1024L

    /** One automatic item as the repository sees it. */
    private data class Item(val id: String, val bytes: Long, val automatic: Boolean, val completed: Boolean)

    /**
     * The order items are removed in, which mirrors the repository's enforcement.
     *
     * Work that has not started goes first, then content that was already written, least valuable
     * first. Manual content is never removed.
     */
    private fun evictionOrder(
        items: List<Item>,
        limitBytes: Long,
    ): List<String> {
        val removed = mutableSetOf<String>()
        val remaining = items.filter { it.automatic }.toMutableList()

        // Work that has not started is cancelled first.
        for (item in remaining.filter { !it.completed }.sortedByDescending { it.id }) {
            val used = remaining.filter { it.id !in removed }.sumOf { it.bytes }
            if (used <= limitBytes) break
            removed += item.id
        }
        // Content that was already written is evicted, oldest first.
        for (item in remaining.filter { it.completed }.sortedBy { it.id }) {
            val used = remaining.filter { it.id !in removed }.sumOf { it.bytes }
            if (used <= limitBytes) break
            removed += item.id
        }
        return removed.toList()
    }

    @Test
    fun `reducing the budget evicts completed automatic content`() {
        val items = listOf(
            Item("a", 100 * megabyte, automatic = true, completed = true),
            Item("b", 100 * megabyte, automatic = true, completed = true),
        )

        val removed = evictionOrder(items, limitBytes = 150 * megabyte)

        assertEquals("expected one item to be evicted", 1, removed.size)
    }

    @Test
    fun `manual content is never evicted`() {
        val items = listOf(
            Item("manual", 400 * megabyte, automatic = false, completed = true),
            Item("auto", 50 * megabyte, automatic = true, completed = true),
        )

        val removed = evictionOrder(items, limitBytes = 100 * megabyte)

        assertTrue("manual content must not be removed, removed=$removed", "manual" !in removed)
    }

    @Test
    fun `each item is removed at most once`() {
        val items = (1..10).map { Item("item$it", 10 * megabyte, automatic = true, completed = true) }

        val removed = evictionOrder(items, limitBytes = 50 * megabyte)

        assertEquals(removed.size, removed.distinct().size)
    }

    @Test
    fun `a budget that is already exceeded still terminates`() {
        val items = listOf(
            Item("a", 100 * megabyte, automatic = true, completed = true),
            Item("b", 100 * megabyte, automatic = true, completed = true),
            Item("c", 100 * megabyte, automatic = true, completed = true),
        )

        val removed = evictionOrder(items, limitBytes = 10 * megabyte)

        assertEquals("everything automatic should go", 3, removed.size)
    }

    @Test
    fun `content that fits is left alone`() {
        val items = listOf(Item("a", 50 * megabyte, automatic = true, completed = true))

        assertTrue(evictionOrder(items, limitBytes = 100 * megabyte).isEmpty())
    }
}
