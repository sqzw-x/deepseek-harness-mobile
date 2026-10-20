package com.labteto.dshmobile.diag

import android.os.Process
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A rolling record of what this process did while nobody was looking at it.
 *
 * The question it exists to answer is whether a missing background notification means the process
 * was frozen, the socket was dead, or nothing actually happened on the host — three causes that
 * look identical from the user's side. Nothing here changes behaviour: it records things that
 * already occur (activity start/stop, connection state, received frames) and adds one minute-marker
 * while backgrounded so a frozen process leaves a hole instead of a silent zero.
 *
 * Deliberately in memory only. A process that was killed loses this log, and that loss — an
 * otherwise idle panel showing only the startup entry — is itself the evidence. Persisting it would
 * make a restart look like a gap in an unbroken history.
 */
@Singleton
class BackgroundTrace @Inject constructor() {

    /** What one entry is about. Labels are the UI's business, not this class's. */
    enum class Kind { PROCESS, FOREGROUND, BACKGROUND, EVENT, SESSION, PHASE, SERVICE, TICK }

    /**
     * One thing that happened, at [at] wall-clock milliseconds.
     *
     * [uptime] is the same instant on the clock that stops while the device is in deep sleep.
     * Comparing the two is what separates a suspended device from a starved process: both leave
     * an identical hole in this table, and only the clocks can tell them apart.
     */
    data class Entry(val at: Long, val uptime: Long, val kind: Kind, val detail: String)

    /**
     * One stretch of time spent in the background.
     *
     * [longestSilenceMs] is the gap between consecutive frames, or between the start of the
     * stretch and its first frame — the number that separates "the host was quiet" from "this
     * process was not running".
     *
     * [asleepMs] is how much of the stretch the device spent in deep sleep, measured as the
     * difference between the wall clock and the uptime clock. A stretch the process slept through
     * has [asleepMs] close to its length; one it was alive but frozen through does not.
     */
    data class Window(
        val since: Long,
        val until: Long,
        val events: Int,
        val longestSilenceMs: Long,
        val asleepMs: Long,
    )

    /**
     * Everything the panel shows.
     *
     * [currentWindow] is non-null only while the process is backgrounded. [lastWindow] is the most
     * recently *finished* stretch, kept because the interesting one is over by the time the panel
     * is on screen: the reader comes back to the app to read it.
     */
    data class Snapshot(
        val pid: Int,
        val startedAt: Long,
        val foreground: Boolean,
        val lastEventAt: Long?,
        val currentWindow: Window?,
        val lastWindow: Window?,
        val entries: List<Entry>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val startedAt = System.currentTimeMillis()

    private var foreground = false
    private var lastEventAt: Long? = null

    // Null until the first transition into the background: a process that has never been
    // backgrounded has no stretch to report, and inventing one from the startup instant would
    // put a few hundred milliseconds of "background" on screen for every cold start.
    private var windowSince: Long? = null
    private var windowSinceUptime: Long = 0L
    private var windowEvents = 0
    private var windowLongestSilenceMs = 0L
    private var windowQuietFrom: Long? = null
    private var lastWindow: Window? = null

    private val _snapshot = MutableStateFlow(build())

    /** The panel's data, republished on every recorded fact. */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        record(Kind.PROCESS, "pid ${Process.myPid()}")
        // The marker that distinguishes a frozen process from a quiet one. A minute is long enough
        // not to keep a phone awake on its own and short enough to leave an obvious hole.
        scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                if (!isForeground()) record(Kind.TICK, "")
            }
        }
    }

    /** Record one fact. [detail] is technical (an event name, a state) and never translated. */
    fun record(kind: Kind, detail: String) {
        val now = System.currentTimeMillis()
        val uptime = SystemClock.uptimeMillis()
        synchronized(lock) {
            when (kind) {
                Kind.FOREGROUND -> {
                    finishWindow(now, uptime)
                    foreground = true
                }

                Kind.BACKGROUND -> {
                    finishWindow(now, uptime)
                    foreground = false
                    windowSince = now
                    windowSinceUptime = uptime
                    windowEvents = 0
                    windowLongestSilenceMs = 0L
                    windowQuietFrom = now
                }

                Kind.EVENT, Kind.SESSION -> {
                    if (!foreground) {
                        windowEvents += 1
                        windowQuietFrom?.let { from ->
                            val silence = now - from
                            if (silence > windowLongestSilenceMs) windowLongestSilenceMs = silence
                        }
                        windowQuietFrom = now
                    }
                    lastEventAt = now
                }

                Kind.PROCESS, Kind.PHASE, Kind.SERVICE, Kind.TICK -> Unit
            }
            entries.addLast(Entry(now, uptime, kind, detail))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        _snapshot.value = build()
    }

    /** Close the open background stretch at [now], keeping it as [lastWindow]. */
    private fun finishWindow(now: Long, uptime: Long) {
        val since = windowSince ?: return
        val silence = windowQuietFrom?.let { now - it } ?: 0L
        lastWindow = Window(
            since = since,
            until = now,
            events = windowEvents,
            longestSilenceMs = maxOf(windowLongestSilenceMs, silence),
            asleepMs = deepSleepMs(now, uptime),
        )
        windowSince = null
        windowEvents = 0
        windowLongestSilenceMs = 0L
        windowQuietFrom = null
    }

    private fun isForeground(): Boolean = synchronized(lock) { foreground }

    /**
     * How much of the window ending at [now] the device spent in deep sleep.
     *
     * Wall time keeps running while the machine is suspended and uptime does not, so the gap
     * between the two clocks is exactly the time no thread on this device could have run. Never
     * negative: a device whose clocks disagree is not something to report as a negative sleep.
     */
    private fun deepSleepMs(now: Long, uptime: Long): Long {
        val since = windowSince ?: return 0L
        val wall = now - since
        val awake = uptime - windowSinceUptime
        return (wall - awake).coerceAtLeast(0L)
    }

    private fun build(): Snapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val uptime = SystemClock.uptimeMillis()
        Snapshot(
            pid = Process.myPid(),
            startedAt = startedAt,
            foreground = foreground,
            lastEventAt = lastEventAt,
            currentWindow = windowSince?.let { since ->
                Window(
                    since = since,
                    until = now,
                    events = windowEvents,
                    longestSilenceMs = maxOf(
                        windowLongestSilenceMs,
                        windowQuietFrom?.let { now - it } ?: 0L,
                    ),
                    asleepMs = deepSleepMs(now, uptime),
                )
            },
            lastWindow = lastWindow,
            entries = entries.toList().asReversed(),
        )
    }

    private companion object {
        const val MAX_ENTRIES = 160
        const val TICK_INTERVAL_MS = 60_000L
    }
}
