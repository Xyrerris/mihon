package eu.kanade.tachiyomi.data.sync

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import java.util.Date
import kotlin.math.max

/**
 * Reconciles two copies of the same manga's reading state, one local and one from somewhere else.
 *
 * It lives here rather than in the restore because a backup restore is not the only merge of two
 * devices, only the first one: an online sync applies the same rules to the same fields, and a rule
 * that exists twice is a rule that will eventually disagree with itself.
 *
 * The rules are OR, max and earliest, and that is deliberate. All three are idempotent, commutative
 * and associative, so applying the same update twice, or two updates in either order, lands on the
 * same state. Devices converge without a coordinator, which is what lets the sync server stay a
 * dumb store of deltas.
 *
 * [Chapter.bookmark] is the exception, and the reason [Chapter.version] is compared: a bookmark is
 * the one field a reader takes back, so OR would make removing one impossible -- any device still
 * holding it would put it back at the next merge. Last write wins instead, on the counter the
 * chapters trigger bumps on every read, bookmark and page change. Ties keep the local value, the
 * same way the manga-level restore keeps the local copy when the versions match.
 */
object SyncMerger {

    /**
     * [local] contributes its identity and its source metadata: it is the copy the source last
     * refreshed, while [remote] is as old as the backup or the last sync. What [remote] contributes
     * is the reading state.
     *
     * The version is the higher of the two rather than the incoming one, so a merge that keeps the
     * local bookmark cannot lower the counter that decided it and hand the next merge the opposite
     * answer.
     */
    fun mergeChapter(local: Chapter, remote: Chapter): Chapter {
        return remote.copyFrom(local).copy(
            id = local.id,
            mangaId = local.mangaId,
            read = local.read || remote.read,
            lastPageRead = max(local.lastPageRead, remote.lastPageRead),
            bookmark = if (remote.version > local.version) remote.bookmark else local.bookmark,
            version = max(local.version, remote.version),
        )
    }

    /**
     * The most recent session wins the date, and the longer one wins the duration.
     *
     * The duration is a maximum and not a sum because history rows are per chapter: summing here
     * would double the time of every chapter both devices have read, while taking a maximum over a
     * whole manga would throw away the time spent on the chapters only one of them read. Per
     * chapter, a maximum gives both.
     *
     * A read date of zero means the history entry was reset, which the reader treats as no date at
     * all, so it is folded back into null instead of counting as a session in 1970.
     */
    fun mergeHistory(local: History, remote: History): History {
        return local.copy(
            readAt = latest(local.readAt, remote.readAt),
            readDuration = max(local.readDuration, remote.readDuration),
        )
    }

    /**
     * Earliest wins, on both fields: the question they answer is when reading started and when it
     * finished, and the first device to know that is right. A null side knows nothing and so never
     * decides the outcome -- which is also what makes a backup written before these fields existed
     * merge into a no-op.
     */
    fun mergeProgress(local: MangaProgressFacts?, remote: MangaProgressFacts): MangaProgressFacts {
        return MangaProgressFacts(
            startedAt = earliest(local?.startedAt, remote.startedAt),
            completedAt = earliest(local?.completedAt, remote.completedAt),
        )
    }

    private fun latest(local: Date?, remote: Date?): Date? {
        return max(local?.time ?: 0L, remote?.time ?: 0L)
            .takeIf { it > 0L }
            ?.let(::Date)
    }

    private fun earliest(local: Long?, remote: Long?): Long? {
        if (local == null) return remote
        if (remote == null) return local
        return minOf(local, remote)
    }
}
