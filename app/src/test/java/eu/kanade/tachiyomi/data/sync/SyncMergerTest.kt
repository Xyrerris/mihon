package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.sync.models.SyncChapter
import eu.kanade.tachiyomi.data.sync.models.SyncHistory
import eu.kanade.tachiyomi.data.sync.models.SyncManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import java.util.Date

class SyncMergerTest {

    private fun chapter(
        id: Long = 1L,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0L,
        version: Long = 0L,
        name: String = "Chapter 1",
    ) = Chapter.create().copy(
        id = id,
        mangaId = 7L,
        url = "/c/1",
        name = name,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        version = version,
    )

    private fun history(
        readAt: Long? = null,
        readDuration: Long = 0L,
    ) = History.create().copy(
        id = 1L,
        chapterId = 1L,
        readAt = readAt?.let(::Date),
        readDuration = readDuration,
    )

    @Test
    fun `When either side has read the chapter expect it to stay read`() {
        val local = chapter(read = true)
        val remote = chapter(read = false)

        SyncMerger.mergeChapter(local, remote).read shouldBe true
        SyncMerger.mergeChapter(remote, local).read shouldBe true
    }

    @Test
    fun `When the page differs expect the further one`() {
        SyncMerger.mergeChapter(chapter(lastPageRead = 12), chapter(lastPageRead = 3))
            .lastPageRead shouldBe 12
        SyncMerger.mergeChapter(chapter(lastPageRead = 3), chapter(lastPageRead = 12))
            .lastPageRead shouldBe 12
    }

    @Test
    fun `When the incoming bookmark is newer expect it to win, removal included`() {
        val local = chapter(bookmark = true, version = 1)
        val remote = chapter(bookmark = false, version = 2)

        SyncMerger.mergeChapter(local, remote).bookmark shouldBe false
    }

    @Test
    fun `When the local bookmark is newer expect the incoming one ignored`() {
        val local = chapter(bookmark = false, version = 3)
        val remote = chapter(bookmark = true, version = 2)

        SyncMerger.mergeChapter(local, remote).bookmark shouldBe false
    }

    @Test
    fun `When the versions tie expect the bookmark kept whichever side holds it`() {
        val bookmarked = chapter(bookmark = true, version = 2)
        val plain = chapter(bookmark = false, version = 2)

        SyncMerger.mergeChapter(plain, bookmarked).bookmark shouldBe true
        SyncMerger.mergeChapter(bookmarked, plain).bookmark shouldBe true
    }

    @Test
    fun `When merging expect the version to be the higher of the two`() {
        SyncMerger.mergeChapter(chapter(version = 5), chapter(version = 2)).version shouldBe 5
        SyncMerger.mergeChapter(chapter(version = 2), chapter(version = 5)).version shouldBe 5
    }

    @Test
    fun `When merging expect the local identity and source metadata kept`() {
        val local = chapter(id = 42, name = "Renamed by the source")
        val remote = chapter(id = 9, name = "Old name")

        val merged = SyncMerger.mergeChapter(local, remote)

        merged.id shouldBe 42
        merged.mangaId shouldBe local.mangaId
        merged.name shouldBe "Renamed by the source"
    }

    @Test
    fun `When the same chapter is merged twice expect the same result`() {
        val local = chapter(read = true, bookmark = true, lastPageRead = 4, version = 1)
        val remote = chapter(read = false, bookmark = false, lastPageRead = 9, version = 3)

        val once = SyncMerger.mergeChapter(local, remote)

        SyncMerger.mergeChapter(once, remote) shouldBe once
    }

    @Test
    fun `When history sessions differ expect the most recent date and the longer duration`() {
        val local = history(readAt = 3_000L, readDuration = 900L)
        val remote = history(readAt = 5_000L, readDuration = 400L)

        val merged = SyncMerger.mergeHistory(local, remote)

        merged.readAt shouldBe Date(5_000L)
        merged.readDuration shouldBe 900L
    }

    @Test
    fun `When one side has no date expect the other one`() {
        SyncMerger.mergeHistory(history(readAt = null), history(readAt = 5_000L))
            .readAt shouldBe Date(5_000L)
        SyncMerger.mergeHistory(history(readAt = 5_000L), history(readAt = null))
            .readAt shouldBe Date(5_000L)
    }

    @Test
    fun `When a date was reset to zero expect no date rather than the epoch`() {
        SyncMerger.mergeHistory(history(readAt = 0L), history(readAt = 0L))
            .readAt shouldBe null
    }

    @Test
    fun `When merging history expect the local row identity kept`() {
        val local = history(readAt = 1_000L).copy(id = 11L, chapterId = 22L)
        val remote = history(readAt = 2_000L).copy(id = 99L, chapterId = 88L)

        val merged = SyncMerger.mergeHistory(local, remote)

        merged.id shouldBe 11L
        merged.chapterId shouldBe 22L
    }

    @Test
    fun `When both sides know when reading started expect the earlier date`() {
        val local = MangaProgressFacts(startedAt = 500L, completedAt = 900L)
        val remote = MangaProgressFacts(startedAt = 200L, completedAt = 1_500L)

        SyncMerger.mergeProgress(local, remote) shouldBe
            MangaProgressFacts(startedAt = 200L, completedAt = 900L)
    }

    @Test
    fun `When one side knows nothing expect the other side to decide`() {
        val known = MangaProgressFacts(startedAt = 200L, completedAt = 900L)
        val unknown = MangaProgressFacts(startedAt = null, completedAt = null)

        SyncMerger.mergeProgress(unknown, known) shouldBe known
        SyncMerger.mergeProgress(known, unknown) shouldBe known
    }

    @Test
    fun `When there is no local row expect the incoming facts`() {
        val remote = MangaProgressFacts(startedAt = 200L, completedAt = null)

        SyncMerger.mergeProgress(null, remote) shouldBe remote
    }

    @Test
    fun `When a backup predates these fields expect the local facts untouched`() {
        val local = MangaProgressFacts(startedAt = 200L, completedAt = 900L)
        val empty = MangaProgressFacts(startedAt = null, completedAt = null)

        SyncMerger.mergeProgress(local, empty) shouldBe local
    }

    @Test
    fun `When facts are merged in either order or twice expect the same result`() {
        val a = MangaProgressFacts(startedAt = 500L, completedAt = null)
        val b = MangaProgressFacts(startedAt = 200L, completedAt = 1_500L)

        val ab = SyncMerger.mergeProgress(a, b)

        SyncMerger.mergeProgress(b, a) shouldBe ab
        SyncMerger.mergeProgress(ab, b) shouldBe ab
        SyncMerger.mergeProgress(ab, a) shouldBe ab
    }

    // The wire rules. Everything above reaches them through the database's own models; a sync
    // reaches them through these, and the two have to keep answering the same way.

    private fun syncChapter(
        url: String = "/c/1",
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0L,
        version: Long = 0L,
    ) = SyncChapter(
        url = url,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        version = version,
    )

    private fun syncManga(
        chapters: List<SyncChapter> = emptyList(),
        history: List<SyncHistory> = emptyList(),
        startedAt: Long? = null,
        completedAt: Long? = null,
        lastModifiedAt: Long = 0L,
    ) = SyncManga(
        source = 3L,
        url = "/m/1",
        chapters = chapters,
        history = history,
        startedAt = startedAt,
        completedAt = completedAt,
        lastModifiedAt = lastModifiedAt,
    )

    @Test
    fun `When a wire chapter is on both sides expect the same rules as the database one`() {
        val local = syncChapter(read = true, lastPageRead = 12, bookmark = true, version = 1)
        val remote = syncChapter(read = false, lastPageRead = 3, bookmark = false, version = 2)

        SyncMerger.mergeChapter(local, remote) shouldBe syncChapter(
            read = true,
            lastPageRead = 12,
            bookmark = false,
            version = 2,
        )
    }

    @Test
    fun `When two devices tie on the version expect the bookmark to survive on both`() {
        val bookmarking = syncChapter(bookmark = true, version = 6)
        val turningAPage = syncChapter(bookmark = false, lastPageRead = 8, version = 6)

        SyncMerger.mergeChapter(bookmarking, turningAPage) shouldBe
            SyncMerger.mergeChapter(turningAPage, bookmarking)
        SyncMerger.mergeChapter(bookmarking, turningAPage).bookmark shouldBe true
    }

    @Test
    fun `When a wire session differs expect the most recent date and the longer duration`() {
        val local = SyncHistory(url = "/c/1", lastRead = 3_000L, readDuration = 900L)
        val remote = SyncHistory(url = "/c/1", lastRead = 5_000L, readDuration = 400L)

        SyncMerger.mergeHistory(local, remote) shouldBe
            SyncHistory(url = "/c/1", lastRead = 5_000L, readDuration = 900L)
    }

    @Test
    fun `When a chapter is only on one side expect it kept rather than dropped`() {
        val local = syncManga(chapters = listOf(syncChapter(url = "/c/1", read = true)))
        val remote = syncManga(chapters = listOf(syncChapter(url = "/c/2", read = true)))

        SyncMerger.mergeManga(local, remote).chapters shouldBe listOf(
            syncChapter(url = "/c/1", read = true),
            syncChapter(url = "/c/2", read = true),
        )
    }

    @Test
    fun `When both sides hold the same chapter expect one merged entry`() {
        val local = syncManga(chapters = listOf(syncChapter(url = "/c/1", read = true, version = 2)))
        val remote = syncManga(chapters = listOf(syncChapter(url = "/c/1", lastPageRead = 9, version = 3)))

        SyncMerger.mergeManga(local, remote).chapters shouldBe listOf(
            syncChapter(url = "/c/1", read = true, lastPageRead = 9, version = 3),
        )
    }

    @Test
    fun `When the entries arrive in any order expect the result ordered by url`() {
        val local = syncManga(
            chapters = listOf(syncChapter(url = "/c/3"), syncChapter(url = "/c/1")),
            history = listOf(SyncHistory(url = "/c/3", lastRead = 1L), SyncHistory(url = "/c/1", lastRead = 2L)),
        )
        val remote = syncManga(chapters = listOf(syncChapter(url = "/c/2")))

        val merged = SyncMerger.mergeManga(local, remote)

        merged.chapters.map { it.url } shouldBe listOf("/c/1", "/c/2", "/c/3")
        merged.history.map { it.url } shouldBe listOf("/c/1", "/c/3")
    }

    @Test
    fun `When a url is repeated within a message expect the copies folded together`() {
        val local = syncManga(
            chapters = listOf(
                syncChapter(url = "/c/1", read = true),
                syncChapter(url = "/c/1", lastPageRead = 4),
            ),
        )

        SyncMerger.mergeManga(local, syncManga()).chapters shouldBe listOf(
            syncChapter(url = "/c/1", read = true, lastPageRead = 4),
        )
    }

    @Test
    fun `When the manga is unknown here expect the incoming state, normalized`() {
        val remote = syncManga(
            chapters = listOf(syncChapter(url = "/c/2"), syncChapter(url = "/c/1", read = true)),
            startedAt = 200L,
            lastModifiedAt = 90L,
        )

        SyncMerger.mergeManga(null, remote) shouldBe syncManga(
            chapters = listOf(syncChapter(url = "/c/1", read = true), syncChapter(url = "/c/2")),
            startedAt = 200L,
            lastModifiedAt = 90L,
        )
    }

    @Test
    fun `When merging a manga expect the dates earliest-wins and the modification date the later`() {
        val local = syncManga(startedAt = 500L, completedAt = 900L, lastModifiedAt = 40L)
        val remote = syncManga(startedAt = 200L, completedAt = null, lastModifiedAt = 70L)

        val merged = SyncMerger.mergeManga(local, remote)

        merged.startedAt shouldBe 200L
        merged.completedAt shouldBe 900L
        merged.lastModifiedAt shouldBe 70L
    }

    // Convergence. These are the reason the rules are what they are: a delta can reach a device
    // twice, or reach two devices in opposite orders, and the devices still have to end up equal.

    private val deviceA = syncManga(
        chapters = listOf(
            syncChapter(url = "/c/1", read = true, version = 4),
            syncChapter(url = "/c/2", lastPageRead = 11, bookmark = true, version = 6),
        ),
        history = listOf(SyncHistory(url = "/c/1", lastRead = 5_000L, readDuration = 900L)),
        startedAt = 500L,
        lastModifiedAt = 40L,
    )

    private val deviceB = syncManga(
        chapters = listOf(
            syncChapter(url = "/c/2", lastPageRead = 3, bookmark = false, version = 6),
            syncChapter(url = "/c/3", read = true, version = 2),
        ),
        history = listOf(
            SyncHistory(url = "/c/1", lastRead = 2_000L, readDuration = 1_500L),
            SyncHistory(url = "/c/3", lastRead = 9_000L),
        ),
        startedAt = 200L,
        completedAt = 12_000L,
        lastModifiedAt = 70L,
    )

    private val deviceC = syncManga(
        chapters = listOf(syncChapter(url = "/c/2", read = true, bookmark = false, version = 9)),
        startedAt = 900L,
        completedAt = 11_000L,
        lastModifiedAt = 55L,
    )

    @Test
    fun `When two devices merge each other expect the same state on both`() {
        SyncMerger.mergeManga(deviceA, deviceB) shouldBe SyncMerger.mergeManga(deviceB, deviceA)
    }

    @Test
    fun `When the same delta arrives twice expect the second one to change nothing`() {
        val once = SyncMerger.mergeManga(deviceA, deviceB)

        SyncMerger.mergeManga(once, deviceB) shouldBe once
        SyncMerger.mergeManga(once, deviceA) shouldBe once
    }

    @Test
    fun `When three devices' deltas arrive in any order expect the same state`() {
        val abThenC = SyncMerger.mergeManga(SyncMerger.mergeManga(deviceA, deviceB), deviceC)
        val acThenB = SyncMerger.mergeManga(SyncMerger.mergeManga(deviceA, deviceC), deviceB)
        val aThenBc = SyncMerger.mergeManga(deviceA, SyncMerger.mergeManga(deviceB, deviceC))

        acThenB shouldBe abThenC
        aThenBc shouldBe abThenC
    }

    @Test
    fun `When a device merges from nothing expect what a device that had it all along has`() {
        val fromNothing = SyncMerger.mergeManga(SyncMerger.mergeManga(null, deviceB), deviceA)

        fromNothing shouldBe SyncMerger.mergeManga(deviceA, deviceB)
    }
}
