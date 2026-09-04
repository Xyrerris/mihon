package eu.kanade.tachiyomi.data.sync.models

import eu.kanade.tachiyomi.data.sync.MangaProgressFacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Everything one manga contributes to a sync: who it is, and what has been read of it.
 *
 * Identity is `(source, url)` and not `mangas._id`, for the reason [SyncChapter] gives about
 * chapters -- the local ids are local. It is the pair MangaRestorer.findExistingManga already
 * resolves through getMangaByUrlAndSourceId, so a device receiving one of these looks the manga up
 * the way it already looks up a manga from a backup.
 *
 * The title and the rest of the source metadata are deliberately absent. A sync of reading progress
 * has no reason to carry a library catalogue to a server the user has to trust with it, and the
 * receiving device gets all of it from the source anyway.
 *
 * [startedAt] and [completedAt] are the two manga_progress columns no device can derive: the first
 * can predate history that has since been cleared, the second cannot be worked out at all by a
 * device that has not finished the manga. Null means this device does not know, never that it did
 * not happen.
 *
 * [lastModifiedAt] is the sending device's own clock, in epoch seconds to match the column it comes
 * from. It is what a push selects on -- see SyncWatermark for why that is not the same number the
 * request sends as `since`.
 */
@Serializable
data class SyncManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val chapters: List<SyncChapter> = emptyList(),
    @ProtoNumber(4) val history: List<SyncHistory> = emptyList(),
    @ProtoNumber(5) val startedAt: Long? = null,
    @ProtoNumber(6) val completedAt: Long? = null,
    @ProtoNumber(7) val lastModifiedAt: Long = 0,
) {

    /** The two dates as the merge takes them, so that one rule serves the backup and the sync. */
    val progress: MangaProgressFacts
        get() = MangaProgressFacts(startedAt = startedAt, completedAt = completedAt)
}
