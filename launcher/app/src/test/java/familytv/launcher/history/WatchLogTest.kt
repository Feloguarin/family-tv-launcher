package familytv.launcher.history

import familytv.launcher.Audience
import familytv.launcher.Service
import familytv.launcher.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

class WatchLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("America/New_York")
    private val bluey = Show("bluey", "Bluey", Service.DISNEY)
    private val puffin = Show("puffin-rock", "Puffin Rock", Service.NETFLIX)
    private val peppa = Show("peppa-pig", "Peppa Pig", null)
    private val severance = Show("severance", "Severance", Service.APPLE)

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun log(file: String = "log.jsonl", max: Int = 2_000) =
        WatchLog(tmp.root.resolve(file), maxEntries = max, clock = { at("2026-10-23T10:00") }, zone = { zone })

    @Test fun recentIsDistinctNewestFirstAndPerAudience() {
        val log = log()
        log.record(bluey, Audience.KIDS, at("2026-10-20T17:00"))
        log.record(puffin, Audience.KIDS, at("2026-10-21T17:00"))
        log.record(severance, Audience.PARENTS, at("2026-10-21T21:00"))
        log.record(bluey, Audience.KIDS, at("2026-10-22T17:00"))
        log.record(peppa, Audience.KIDS, at("2026-10-22T08:00"))

        assertEquals(listOf("bluey", "peppa-pig", "puffin-rock"), log.recent(Audience.KIDS).map { it.showId })
        assertEquals(listOf("bluey", "peppa-pig"), log.recent(Audience.KIDS, limit = 2).map { it.showId })
        assertEquals(listOf("severance"), log.recent(Audience.PARENTS).map { it.showId })
        assertEquals(at("2026-10-22T17:00"), log.lastWatched("bluey")?.at)
        assertNull(log.lastWatched("doraemon"))
    }

    @Test fun persistsAcrossInstancesAndSkipsCorruptLines() {
        log().record(bluey, Audience.KIDS, at("2026-10-22T17:00"))
        tmp.root.resolve("log.jsonl").appendText("{not json\n\n{\"showId\":\"x\"}\n")
        log().record(severance, Audience.PARENTS, at("2026-10-22T21:00"))

        val reloaded = log()
        assertEquals(listOf("bluey", "severance"), reloaded.all().map { it.showId })
        val entry = reloaded.lastWatched("bluey")!!
        assertEquals(Service.DISNEY, entry.service)
        assertEquals(Audience.KIDS, entry.audience)
        assertNull(reloaded.lastWatched("peppa-pig"))
    }

    @Test fun keepsOnlyTheNewestEntriesWhenFull() {
        val log = log(max = 5)
        repeat(8) { i -> log.append(WatchEntry("show-$i", "Show $i", null, Audience.KIDS, 1_000L + i)) }
        assertEquals((3..7).map { "show-$it" }, log.all().map { it.showId })
        assertEquals((3..7).map { "show-$it" }, log(max = 5).all().map { it.showId })
        assertEquals(5, tmp.root.resolve("log.jsonl").readLines().count { it.isNotBlank() })
    }

    @Test fun concurrentAppendsAreAllKept() {
        val log = log()
        (0 until 8).map { t ->
            thread { repeat(50) { i -> log.append(WatchEntry("s$t-$i", "S", null, Audience.KIDS, i.toLong())) } }
        }.forEach { it.join() }
        assertEquals(400, log.all().size)
        assertEquals(400, log().all().size)
    }

    @Test fun labelsReadLikeACouchWouldSayThem() {
        val now = at("2026-10-23T10:00") // a Friday morning
        fun label(t: String) = WatchLog.whenLabel(at(t), now, zone)
        assertEquals("today", label("2026-10-23T08:00"))
        assertEquals("last night", label("2026-10-22T21:00"))
        assertEquals("last night", label("2026-10-23T01:30")) // after midnight is still last night
        assertEquals("yesterday", label("2026-10-22T13:00"))
        assertEquals("Tuesday", label("2026-10-20T18:00"))
        assertEquals("Saturday", label("2026-10-17T12:00"))
        assertEquals("Oct 16", label("2026-10-16T12:00"))
        assertEquals("Oct 12", label("2026-10-12T19:00"))
        assertEquals("Dec 30, 2025", label("2025-12-30T19:00"))
        assertEquals("tonight", WatchLog.whenLabel(at("2026-10-23T19:00"), at("2026-10-23T22:00"), zone))
    }

    @Test fun lastWatchedLabel() {
        val log = log()
        log.record(bluey, Audience.KIDS, at("2026-10-22T19:30"))
        assertEquals("Last watched last night", log.lastWatchedLabel("bluey"))
        assertNull(log.lastWatchedLabel("doraemon"))
    }
}
