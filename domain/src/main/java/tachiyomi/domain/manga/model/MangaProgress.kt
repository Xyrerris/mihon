package tachiyomi.domain.manga.model

/**
 * Materialized reading progress for one manga, mirroring the manga_progress table.
 *
 * Every field except [startedAt] and [completedAt] is derived from chapters and history, so a row
 * is disposable: recalculating rebuilds it. Timestamps are epoch milliseconds, except
 * [lastModifiedAt], which is in seconds to match the identically named column on [Manga].
 *
 * [bookmarkedChapterCount], [latestUploadAt] and [latestFetchAt] are not progress. They are stored
 * alongside it because the library needs them on the same row, and reading them from here is what
 * spares libraryView the aggregate it used to run on every emission.
 */
data class MangaProgress(
    val mangaId: Long,
    val lastReadChapterId: Long?,
    val lastReadChapterNumber: Double?,
    val lastReadPage: Long,
    val readChapterCount: Long,
    val totalChapterCount: Long,
    val bookmarkedChapterCount: Long,
    val progressPercent: Double,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastReadAt: Long?,
    val latestUploadAt: Long,
    val latestFetchAt: Long,
    val totalReadDuration: Long,
    val isStale: Boolean,
    val lastModifiedAt: Long,
    val version: Long,
) {

    val unreadCount: Long
        get() = totalChapterCount - readChapterCount

    val hasStarted: Boolean
        get() = readChapterCount > 0

    val isCompleted: Boolean
        get() = completedAt != null
}
