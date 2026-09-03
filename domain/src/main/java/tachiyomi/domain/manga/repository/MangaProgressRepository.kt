package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaProgress

interface MangaProgressRepository {

    suspend fun getProgressByMangaId(mangaId: Long): MangaProgress?

    fun getProgressByMangaIdAsFlow(mangaId: Long): Flow<MangaProgress?>

    suspend fun getStaleMangaIds(): List<Long>

    fun getStaleMangaIdsAsFlow(): Flow<List<Long>>

    suspend fun recalculate(mangaId: Long)

    suspend fun recalculateAll(mangaIds: List<Long>)

    suspend fun markStale(mangaId: Long)

    suspend fun markAllStale()
}
