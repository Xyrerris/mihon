package tachiyomi.data.manga

import tachiyomi.domain.manga.model.MangaProgress

object MangaProgressMapper {
    // isSyncing is storage bookkeeping for the restore and sync paths, the same as on mangas and
    // chapters, so it does not reach the domain model.
    @Suppress("UNUSED_PARAMETER")
    fun mapMangaProgress(
        mangaId: Long,
        lastReadChapterId: Long?,
        lastReadChapterNumber: Double?,
        lastReadPage: Long,
        readChapterCount: Long,
        totalChapterCount: Long,
        progressPercent: Double,
        startedAt: Long?,
        completedAt: Long?,
        lastReadAt: Long?,
        totalReadDuration: Long,
        isStale: Boolean,
        lastModifiedAt: Long,
        version: Long,
        isSyncing: Long,
    ): MangaProgress = MangaProgress(
        mangaId = mangaId,
        lastReadChapterId = lastReadChapterId,
        lastReadChapterNumber = lastReadChapterNumber,
        lastReadPage = lastReadPage,
        readChapterCount = readChapterCount,
        totalChapterCount = totalChapterCount,
        progressPercent = progressPercent,
        startedAt = startedAt,
        completedAt = completedAt,
        lastReadAt = lastReadAt,
        totalReadDuration = totalReadDuration,
        isStale = isStale,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}
