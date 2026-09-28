package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaProgress

interface MangaProgressRepository {

    suspend fun getProgressByMangaId(mangaId: Long): MangaProgress?

    fun getProgressByMangaIdAsFlow(mangaId: Long): Flow<MangaProgress?>

    suspend fun getStaleMangaIds(): List<Long>

    suspend fun getDivergentMangaIds(): List<Long>

    fun getStaleMangaIdsAsFlow(): Flow<List<Long>>

    suspend fun recalculate(mangaId: Long)

    suspend fun recalculateAll(mangaIds: List<Long>)

    /**
     * Writes [startedAt] and [completedAt] as given and flags the row, so the derived columns are
     * rebuilt by whoever recalculates next. The caller has already merged them: the rule lives in
     * SyncMerger, not here.
     */
    suspend fun upsertProgressFacts(mangaId: Long, startedAt: Long?, completedAt: Long?)

    suspend fun markStale(mangaId: Long)

    suspend fun markAllStale()
}
