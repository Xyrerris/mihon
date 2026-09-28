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
 * The restore itself no longer calls [mergeChapter] or [mergeHistory]. Upstream moved it into
 * RestoreRepositoryImpl, in the data module, with the same rules written inline: read and bookmark
 * are OR'd, the page, the read date and the duration take the maximum. The fork leaves that file as
 * upstream ships it, so that the next upstream change to the restore merges cleanly, and keeps the
 * rules here for the sync. Nothing enforces that the two stay the same; an upstream merge that
 * touches restoreChapters or restoreHistory is the moment to compare them again.
 *
 * The rules are OR, max and earliest, and that is deliberate. All three are idempotent, commutative
 * and associative, so applying the same update twice, or two updates in either order, lands on the
 * same state. Devices converge without a coordinator, which is what lets the sync server stay a
 * dumb store of deltas.
 *
 * [Chapter.bookmark] is the one field a reader takes back, which OR cannot express: any device
 * still holding a bookmark puts it back at the next merge. It is OR here all the same, because
 * there is nothing to order two bookmarks by: the per-chapter version counter it used to be decided
 * on was upstream's, and upstream dropped it together with the rest of that sync scaffolding. The
 * sync will need an order of its own for this field, kept on a table the fork owns.
 */
object SyncMerger {

    /**
     * [local] contributes its identity and its source metadata: it is the copy the source last
     * refreshed, while [remote] is as old as the backup or the last sync. What [remote] contributes
     * is the reading state.
     */
    fun mergeChapter(local: Chapter, remote: Chapter): Chapter {
        return remote.copyFrom(local).copy(
            id = local.id,
            mangaId = local.mangaId,
            read = local.read || remote.read,
            lastPageRead = max(local.lastPageRead, remote.lastPageRead),
            bookmark = local.bookmark || remote.bookmark,
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
