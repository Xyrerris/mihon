package tachiyomi.domain.manga.model

/**
 * Materialized reading progress for one manga, mirroring the manga_progress table.
 *
 * Every field except [startedAt] and [completedAt] is derived from chapters and history, so a row
 * is disposable: recalculating rebuilds it. Timestamps are epoch milliseconds, except
 * [lastModifiedAt], which is in seconds to match the identically named column on [Manga].
 */
data class MangaProgress(
    val mangaId: Long,
    val lastReadChapterId: Long?,
    val lastReadChapterNumber: Double?,
    val lastReadPage: Long,
    val readChapterCount: Long,
    val totalChapterCount: Long,
    val progressPercent: Double,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastReadAt: Long?,
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
