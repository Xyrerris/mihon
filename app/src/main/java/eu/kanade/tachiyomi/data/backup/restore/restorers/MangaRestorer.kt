package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.MangaProgressFacts
import eu.kanade.tachiyomi.data.sync.SyncMerger
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaProgressRepository
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
    private val mangaProgressRepository: MangaProgressRepository,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()

        return backupMangas
            .sortedBy { it.url in urlsBySource[it.source].orEmpty() }
    }

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategoriesByName = getCategories.await().associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            RestoredManga(
                manga = backupManga.getMangaImpl(),
                chapters = backupManga.chapters.map { it.toChapterImpl() },
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
            )
        }

        val progressByManga = backupMangas
            .mapNotNull { backupManga ->
                backupManga.progress?.let { (backupManga.source to backupManga.url) to it.toFacts() }
            }
            .toMap()

        // The callback runs once an entry's chapters and history are in place, inside the same
        // all-or-nothing restore, which is where the reading dates belong: restored with the rest of
        // the entry or not at all.
        restoreRepository.restoreManga(entries) {
            progressByManga[it.source to it.url]?.let { progress -> restoreProgress(it, progress) }
            fetchInterval.withFetchInterval(it, now, timeZone, currentFetchWindow)
        }
    }

    /**
     * Merges the two reading dates the backup carries. Nothing else of manga_progress is restored:
     * the counts, the percentage and the resume point are recomputed from the chapters and history
     * restored alongside, once BackupRestorer rebuilds what the restore flagged.
     *
     * A backup written before those dates existed carries none, and never gets here.
     */
    private suspend fun restoreProgress(manga: Manga, progress: MangaProgressFacts) {
        val local = mangaProgressRepository.getProgressByMangaId(manga.id)
            ?.let { MangaProgressFacts(startedAt = it.startedAt, completedAt = it.completedAt) }
        val merged = SyncMerger.mergeProgress(local = local, remote = progress)
        if (merged == local) return

        mangaProgressRepository.upsertProgressFacts(
            mangaId = manga.id,
            startedAt = merged.startedAt,
            completedAt = merged.completedAt,
        )
    }
}
