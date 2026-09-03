package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.repository.MangaProgressRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaProgressRepositoryImpl(
    private val database: Database,
) : MangaProgressRepository {

    override suspend fun getProgressByMangaId(mangaId: Long): MangaProgress? {
        return database.manga_progressQueries
            .getProgressByMangaId(mangaId, MangaProgressMapper::mapMangaProgress)
            .awaitAsOneOrNull()
    }

    override fun getProgressByMangaIdAsFlow(mangaId: Long): Flow<MangaProgress?> {
        return database.manga_progressQueries
            .getProgressByMangaId(mangaId, MangaProgressMapper::mapMangaProgress)
            .subscribeToOneOrNull()
    }

    override suspend fun getStaleMangaIds(): List<Long> {
        return database.manga_progressQueries
            .getStaleMangaIds()
            .awaitAsList()
    }

    override suspend fun recalculate(mangaId: Long) {
        database.manga_progressQueries.recalculateForManga(mangaId)
    }

    // One transaction for the whole batch: the upsert is cheap, the round trips are not.
    override suspend fun recalculateAll(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        database.transaction {
            mangaIds.forEach { database.manga_progressQueries.recalculateForManga(it) }
        }
    }

    override suspend fun markStale(mangaId: Long) {
        database.manga_progressQueries.markStale(mangaId)
    }

    override suspend fun markAllStale() {
        database.manga_progressQueries.markAllStale()
    }
}
