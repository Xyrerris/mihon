package tachiyomi.data.manga

import tachiyomi.domain.manga.model.MangaProgress

object MangaProgressMapper {
    fun mapMangaProgress(
        mangaId: Long,
        lastReadChapterId: Long?,
        lastReadChapterNumber: Double?,
        lastReadPage: Long,
        readChapterCount: Long,
        totalChapterCount: Long,
        bookmarkedChapterCount: Long,
        progressPercent: Double,
        startedAt: Long?,
        completedAt: Long?,
        lastReadAt: Long?,
        latestUploadAt: Long,
        latestFetchAt: Long,
        totalReadDuration: Long,
        isStale: Boolean,
        lastModifiedAt: Long,
        version: Long,
    ): MangaProgress = MangaProgress(
        mangaId = mangaId,
        lastReadChapterId = lastReadChapterId,
        lastReadChapterNumber = lastReadChapterNumber,
        lastReadPage = lastReadPage,
        readChapterCount = readChapterCount,
        totalChapterCount = totalChapterCount,
        bookmarkedChapterCount = bookmarkedChapterCount,
        progressPercent = progressPercent,
        startedAt = startedAt,
        completedAt = completedAt,
        lastReadAt = lastReadAt,
        latestUploadAt = latestUploadAt,
        latestFetchAt = latestFetchAt,
        totalReadDuration = totalReadDuration,
        isStale = isStale,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}
