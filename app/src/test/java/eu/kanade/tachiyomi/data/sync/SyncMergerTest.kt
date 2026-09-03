package eu.kanade.tachiyomi.data.sync

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
    fun `When the versions tie expect the local bookmark kept`() {
        val local = chapter(bookmark = false, version = 2)
        val remote = chapter(bookmark = true, version = 2)

        SyncMerger.mergeChapter(local, remote).bookmark shouldBe false
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
}
