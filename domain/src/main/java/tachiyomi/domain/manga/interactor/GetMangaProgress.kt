package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.repository.MangaProgressRepository

@Inject
class GetMangaProgress(
    private val mangaProgressRepository: MangaProgressRepository,
) {

    /**
     * Returns the stored row, which is null for a manga that has never had one written, and may be
     * stale: a trigger flags a row on every write that can move progress, and only
     * [RecalculateMangaProgress] brings it back up to date.
     */
    suspend fun await(mangaId: Long): MangaProgress? {
        return try {
            mangaProgressRepository.getProgressByMangaId(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    fun subscribe(mangaId: Long): Flow<MangaProgress?> {
        return mangaProgressRepository.getProgressByMangaIdAsFlow(mangaId)
    }
}
