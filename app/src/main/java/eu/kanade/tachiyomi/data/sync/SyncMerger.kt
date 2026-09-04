package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.sync.models.SyncChapter
import eu.kanade.tachiyomi.data.sync.models.SyncHistory
import eu.kanade.tachiyomi.data.sync.models.SyncManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import java.util.Date
import kotlin.math.max

/**
 * Reconciles two copies of the same reading state, one held by this device and one from somewhere
 * else -- a backup being restored, or another device's changes coming back from a sync server.
 *
 * The rules live here and nowhere else because there are two callers and there will not be a third
 * copy of them: a rule that exists twice is a rule that will eventually disagree with itself. The
 * restore reaches them through [mergeChapter] and [mergeHistory] on the database's own models; a
 * sync reaches the same rules through [mergeManga] on the wire models. The second pair is written
 * in terms of the first, so a bug in either shows up in both sets of tests.
 *
 * ## Why these rules converge
 *
 * Every field is folded with an operation that is idempotent, commutative and associative -- OR for
 * "has been read", maximum for how far a page or a counter got, earliest for a date that records a
 * first time. Applying the same delta twice, or two deltas in either order, therefore lands on the
 * same state. That is a join-semilattice, and it is what lets the server stay a dumb store of
 * deltas: no coordinator, no locks, no ordering guarantees to honour.
 *
 * [SyncChapter.bookmark] is the field that does not fit, and the only one that needs
 * [SyncChapter.version]. A bookmark is taken back as well as given, so OR would make removing one
 * impossible: any device still holding it would put it back at the next merge. Last write wins
 * instead, on the counter the chapters trigger bumps on every read, bookmark and page change.
 *
 * An exact tie is the case worth spelling out. It is not a corner: two devices at the same counter
 * that both wrote once -- one bookmarking, one turning a page -- tie with different answers. Left
 * as "the local copy keeps its value" the two would each keep their own and never agree again, so
 * the tie is broken the one way that is symmetric and does not lose the deliberate act: the
 * bookmark stays. Read over a whole history of merges the rule is "the bookmarks set at the highest
 * version seen, OR'd together", which is a lattice element like the others. Removing a bookmark
 * that a tie brought back costs one more toggle, and that toggle bumps the counter and wins
 * outright.
 */
object SyncMerger {

    /**
     * Merges one manga's whole reading state, which is what a sync exchanges.
     *
     * [local] is what this device holds for the manga, or null when the entry arrived for a manga
     * with no state here yet. [remote] is the other side's. Both are assumed to name the same
     * `(source, url)`; pairing them is the caller's job, since only the caller can resolve a url to
     * a local manga.
     *
     * Chapters and history are matched by url and the ones only one side has are kept as they are:
     * a chapter missing on the other device has not been rejected, it has not been fetched. The
     * result is ordered by url, so merging the same two states in either order gives a value that
     * is equal and not merely equivalent.
     */
    fun mergeManga(local: SyncManga?, remote: SyncManga): SyncManga {
        val base = local ?: SyncManga(source = remote.source, url = remote.url)
        val progress = mergeProgress(local = base.progress, remote = remote.progress)
        return SyncManga(
            source = base.source,
            url = base.url,
            chapters = mergeByUrl(base.chapters, remote.chapters, SyncChapter::url, ::mergeChapter),
            history = mergeByUrl(base.history, remote.history, SyncHistory::url, ::mergeHistory),
            startedAt = progress.startedAt,
            completedAt = progress.completedAt,
            lastModifiedAt = max(base.lastModifiedAt, remote.lastModifiedAt),
        )
    }

    /**
     * Read is monotonic, the page within a chapter only moves forward, and the version is the
     * higher of the two so that a merge which kept the local bookmark cannot lower the counter that
     * decided it and hand the next merge the opposite answer.
     */
    fun mergeChapter(local: SyncChapter, remote: SyncChapter): SyncChapter {
        return local.copy(
            read = local.read || remote.read,
            lastPageRead = max(local.lastPageRead, remote.lastPageRead),
            bookmark = when {
                remote.version > local.version -> remote.bookmark
                local.version > remote.version -> local.bookmark
                else -> local.bookmark || remote.bookmark
            },
            version = max(local.version, remote.version),
        )
    }

    /**
     * The most recent session wins the date, and the longer one wins the duration.
     *
     * The duration is a maximum and not a sum because these rows are per chapter: summing would
     * double the time of every chapter both devices have read, while taking a maximum over a whole
     * manga would throw away the time spent on the chapters only one of them read. Per chapter, a
     * maximum gives both.
     */
    fun mergeHistory(local: SyncHistory, remote: SyncHistory): SyncHistory {
        return local.copy(
            lastRead = max(local.lastRead, remote.lastRead),
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

    /**
     * [local] contributes its identity and its source metadata: it is the copy the source last
     * refreshed, while [remote] is as old as the backup or the last sync. What [remote] contributes
     * is the reading state, and which of the two wins each field is the wire rule above.
     */
    fun mergeChapter(local: Chapter, remote: Chapter): Chapter {
        val merged = mergeChapter(local.asSyncChapter(), remote.asSyncChapter())
        return remote.copyFrom(local).copy(
            id = local.id,
            mangaId = local.mangaId,
            read = merged.read,
            lastPageRead = merged.lastPageRead,
            bookmark = merged.bookmark,
            version = merged.version,
        )
    }

    /**
     * The same session rule, reached from the row the database keeps it in rather than from the
     * wire. The local row identity is kept, and a merged date of zero folds back into null, which
     * is how the reader reads a history entry whose date was cleared.
     */
    fun mergeHistory(local: History, remote: History): History {
        val merged = mergeHistory(local.asSyncHistory(), remote.asSyncHistory())
        return local.copy(
            readAt = merged.lastRead.takeIf { it > 0L }?.let(::Date),
            readDuration = merged.readDuration,
        )
    }

    private fun Chapter.asSyncChapter() = SyncChapter(
        url = url,
        read = read,
        lastPageRead = lastPageRead,
        bookmark = bookmark,
        version = version,
    )

    private fun History.asSyncHistory() = SyncHistory(
        // A history row is addressed by chapter id here and by chapter url on the wire, and the
        // merge decides neither, so there is nothing for the adapter to put in the key.
        url = "",
        lastRead = readAt?.time ?: 0L,
        readDuration = readDuration,
    )

    /**
     * Folds two lists keyed by url into one, merging the entries both sides have and keeping the
     * rest. Entries repeated within a list are folded by the same rule, so a sender that duplicates
     * a url cannot make the result depend on which copy is read last.
     */
    private fun <T> mergeByUrl(
        local: List<T>,
        remote: List<T>,
        url: (T) -> String,
        merge: (T, T) -> T,
    ): List<T> {
        if (local.isEmpty() && remote.isEmpty()) return emptyList()
        val byUrl = LinkedHashMap<String, T>(local.size + remote.size)
        (local + remote).forEach { entry ->
            val key = url(entry)
            val existing = byUrl[key]
            byUrl[key] = if (existing == null) entry else merge(existing, entry)
        }
        return byUrl.values.sortedBy(url)
    }

    private fun earliest(local: Long?, remote: Long?): Long? {
        if (local == null) return remote
        if (remote == null) return local
        return minOf(local, remote)
    }
}
