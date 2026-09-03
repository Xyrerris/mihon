package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaProgressRepository

/**
 * The bulk half of the invalidation strategy. The triggers only flag rows; recalculation is
 * deliberately a separate step so that a restore or a library update pays for it once, at a cost
 * linear in manga rather than in the chapters written.
 */
@Inject
class RecalculateMangaProgress(
    private val mangaProgressRepository: MangaProgressRepository,
) {

    suspend fun await(mangaId: Long): Boolean {
        return try {
            mangaProgressRepository.recalculate(mangaId)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    /**
     * Recalculates every row a trigger has flagged, in one transaction, and returns how many were
     * rebuilt. A zero means there was nothing to do, or that the recalculation failed and the rows
     * stayed flagged for the next caller.
     */
    suspend fun awaitStale(): Int {
        return try {
            val mangaIds = mangaProgressRepository.getStaleMangaIds()
            mangaProgressRepository.recalculateAll(mangaIds)
            mangaIds.size
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            0
        }
    }

    /**
     * Rebuilds every row whose stored counts no longer match the chapter list, and returns how
     * many there were.
     *
     * There should never be one: every write path the app has today goes through a staleness
     * trigger, and MangaProgressMaintainer rebuilds what they flag. This is for the write path
     * they do not see -- one added later, or one that reaches the database from outside the app --
     * because without it the failure mode is a library quietly showing the wrong numbers, with
     * nothing flagged to say so. It costs the aggregate that materializing the table took out of
     * the library flow, which is why it belongs to the library update and not to every emission.
     *
     * A non-zero result is worth knowing about: it means a write escaped the triggers.
     */
    suspend fun awaitDivergent(): Int {
        return try {
            val mangaIds = mangaProgressRepository.getDivergentMangaIds()
            if (mangaIds.isNotEmpty()) {
                logcat(LogPriority.WARN) {
                    "Rebuilding ${mangaIds.size} manga_progress rows that no trigger had flagged"
                }
                mangaProgressRepository.recalculateAll(mangaIds)
            }
            mangaIds.size
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            0
        }
    }

    /**
     * Flags every manga and rebuilds the table from scratch. This is the recovery path for a write
     * that bypassed the triggers, not something a normal write should ever need.
     */
    suspend fun awaitAll(): Int {
        return try {
            mangaProgressRepository.markAllStale()
            awaitStale()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            0
        }
    }
}
