package com.labteto.dshmobile.diag

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Where one session switch spends its rendering time.
 *
 * Every field is a running total reset when a switch publishes its first snapshot, so the numbers
 * read as "what it cost to paint this session". Process-wide and lock-free on purpose: the
 * renderers are composables with no injection point, and a few atomics are cheaper than threading
 * a clock through every one of them.
 *
 * The split exists because the fixes are unrelated: parsing markdown is CPU on text, laying it
 * out is CPU on glyphs, and highlighting code is a third lexer again. A single "render is slow"
 * number cannot say which one to attack.
 */
object RenderStats {

    private val markdownMs = AtomicLong()
    private val markdownBlocks = AtomicLong()
    private val inlineMs = AtomicLong()
    private val codeMs = AtomicLong()
    private val mathMs = AtomicLong()
    private val chars = AtomicLong()
    private val largest = AtomicLong()
    private val slots = AtomicLong()
    private val slotMs = AtomicLong()
    private val links = AtomicLong()
    private val selections = AtomicLong()

    // One switch's row stamps in composition order. Main-thread only: every write comes from the
    // transcript's items lambda and every read from the layout callback that follows it.
    private val rowStamps = ArrayList<Pair<String, Long>>()

    fun reset() {
        markdownMs.set(0)
        markdownBlocks.set(0)
        inlineMs.set(0)
        codeMs.set(0)
        mathMs.set(0)
        chars.set(0)
        largest.set(0)
        slots.set(0)
        slotMs.set(0)
        links.set(0)
        selections.set(0)
        // Cleared from the store's dispatcher while the main thread may be mid-composition.
        synchronized(rowStamps) { rowStamps.clear() }
    }

    /** How much text one switch puts through the parser at all — the scale every other field is relative to. */
    fun countChars(length: Int) {
        chars.addAndGet(length.toLong())
        val seen = largest.get()
        if (length > seen) largest.compareAndSet(seen, length.toLong())
    }

    fun <T> markdown(block: () -> T): T {
        markdownBlocks.incrementAndGet()
        return time(markdownMs, block)
    }

    fun <T> inline(block: () -> T): T = time(inlineMs, block)

    /**
     * Building a text's inline slots: one `TextMeasurer.measure` per file pill, which is the only
     * place the renderer measures text of its own before Compose does.
     */
    fun addSlots(count: Int, elapsedMs: Long) {
        slots.addAndGet(count.toLong())
        slotMs.addAndGet(elapsedMs)
    }

    fun countLinks(count: Int) {
        links.addAndGet(count.toLong())
    }

    /** Texts that went through the selection branch, which lays out inside a container. */
    fun addSelection() {
        selections.incrementAndGet()
    }

    fun <T> code(block: () -> T): T = time(codeMs, block)

    fun <T> math(block: () -> T): T = time(mathMs, block)

    /** Stamp the moment one row starts composing; the gap to the next one is what it cost. */
    fun rowStart(label: String) {
        val stamp = SystemClock.uptimeMillis()
        synchronized(rowStamps) { rowStamps.add(label to stamp) }
    }

    /** When the first row of this switch began composing, or 0 when none did. */
    fun firstRowAt(): Long = synchronized(rowStamps) { rowStamps.firstOrNull()?.second } ?: 0L

    /**
     * What the rows cost, in the order they composed.
     *
     * Gaps rather than spans: Compose composes rows one after another on the main thread, so the
     * distance between two stamps is the time the earlier row took — including measurement, which
     * for a lazy list is where the items are actually built.
     */
    fun rowSummary(): String {
        val stamps = synchronized(rowStamps) { rowStamps.toList() }
        if (stamps.size < 2) return "rowsum=0ms top=none"
        val gaps = ArrayList<Pair<String, Long>>()
        var total = 0L
        for (i in 0 until stamps.size - 1) {
            val gap = stamps[i + 1].second - stamps[i].second
            total += gap
            gaps.add(stamps[i].first to gap)
        }
        val top = gaps.sortedByDescending { it.second }.take(3)
            .joinToString("/") { "${it.first}:${it.second}" }
        return "rowsum=${total}ms top=$top"
    }

    /** One line for the diagnostics trace: totals, and what they were spent on. */
    fun summary(): String =
        "md=${markdownMs.get()}ms/${markdownBlocks.get()} inline=${inlineMs.get()}ms " +
            "code=${codeMs.get()}ms math=${mathMs.get()}ms chars=${chars.get()} max=${largest.get()} " +
            "slots=${slots.get()}/${slotMs.get()}ms links=${links.get()} sel=${selections.get()}"

    private inline fun <T> time(counter: AtomicLong, block: () -> T): T {
        val start = SystemClock.uptimeMillis()
        val result = block()
        counter.addAndGet(SystemClock.uptimeMillis() - start)
        return result
    }
}
